**JSMPPGW** is an open-source **JMS/ActiveMQ** <-> **SMPP** gateway. 

The basic usecase: your SMS operator wants you to send and receive SMS messages (usually in bulk) through SMPP, but you want simpler and more standard way to do it.

Features
========

 * Supports active/active clustering out of the box (underlying JMS, such as ActiveMQ, has to be in active-active mode too).
 * Graceful degradation and retrying of unsent messages.
 * Can be run on any container (Jetty and Glassfish were tested).
 * Is open-source (Apache License 2.0)
 * Is manageable through Java Management Interface. Monitoring, statistics and error reporting available.
 * Tested in production
 * Supports sending of long messages
 * Supports receiving of messages
 
It uses jsmpp library, Apache Commons and command line jmx client for status view.

Introduction
============

SMS gateway uses SMPP protocol to connect to SMPP gateway (tested
on T-Mobile Slovakia).

It consumes messages from JMS provider (tested with ActiveMQ). Also
delivers SMPP incoming SMS to JMS queue (not heavily tested).

JSMPP was tested in production in Jetty, somewhat tested running in
Glassfish.

Setup
=====

Unpack ```jsmppgw.war``` (from the release tarball) to Jetty's ```webapps/jsmppgw``` (beware, there's no directory!). For example if your Jetty is in /opt/activemq (we happen to run ActiveMQ also on the same Jetty cluster):

    cd /opt/activemq/webapps
    rm -rf jsmppgw; mkdir jsmppgw
    cd jsmppgw
    jar xvf ~/jsmpp.war

Edit ```/opt/activemq/conf/activemq-jetty.xml```, add

            <webAppContext contextPath="/jsmppgw" resourceBase="/opt/activemq/webapps/jsmppgw"
	     logUrlOnStart="true"/>

to ```beans/jetty/handlers``` part of XML

3.) Edit ```/opt/activemq/webapps/jsmppgw/WEB-INF/jsmpp.properties```

4.) Restart Jetty

Sending of messages
===================

Messages are sent to queue *smsQueue* (in default configuration).
They have to be JSON formatted.  JSON should produce this JavaBean
(getters and setters ommited):

    public class ShortMessage {
      public static final short SENDER_TYPE_LONG = 0;
      public static final short SENDER_TYPE_SHORT = 1;
      public static final short SENDER_TYPE_ALPHANUMERIC = 2;

      private String destinationNumber;
      private String textContent;
      private short fromType = SENDER_TYPE_LONG;
    }

Destination number is international format without the leading "+".

Example message formatted in JSON:

    {"destinationNumber":"421123123123","fromType":2,"textContent":"This is a test"}

If message is longer than 160 characters, it automatically gets
converted to several long messages and the parts are put back into
the queue.

Program description
===================

jsmppgw tries to connect to SMPP server. If it is not possible
(status == uninitialized or status == disconnected), it does nothing
(does _not_ receive nor process messages).

When it processes messages, it tries to deliver them. If it's not
possible, it tries to reconnect to SMPP gateway. After few tries
(three by default), it stops trying and redelivers the message back
to queue (so other node in the cluster can process it).  If this
is not possible (error in returning), it returns exception (so
message should be not accepted, but remains in queue and will be
hopefully redelivered).

It provides JMX interface to all statistics, etc., so it can be
easily monitored.

Receiving of messages
=====================

Receiving messages from SMPP gateway and putting it into queue
with configured name (smsIncomingQueue by default).

Messages are in JSON format:

    {"destinationNumber":"421903123123","fromType":0,"retransmits":0,"sarFormattedMessage":false,"sarMsgRefNum":0,"sarSegmentNumber":0,"sarTotalSegments":1,"senderNumber":"421903333333","textContent":"This is a test of a reply."}

Which is converted to ShortMessage JavaBean again (but makes use of more
properties):

    public class ShortMessage {
      private String senderNumber; // ignored when sending
      private String destinationNumber;
      private String textContent;
      private short fromType = SENDER_TYPE_LONG; // ignored when receiving
      private int retransmits = 0;

      private boolean sarFormattedMessage = false;
      private short sarMsgRefNum = 0;
      private byte sarSegmentNumber = 0;
      private byte sarTotalSegments = 1;

      // getters and setters omitted
    }

Long messages are currently _not_ parsed, nor paired (so you can ignore
sarFormattedMessage when receiving for now).


Testing
=======

Testing of SMS gateway can be done using stomp.py (STOMP is one of
the wire protocols supported by ActiveMQ).

    python stomp/cli.py activemq1.digmia.com 61618
    > send /queue/smsQueue {"destinationNumber":"421903333333","fromType":2,"textContent":"This is a test"}

("> " is prompt of stomp.py, should not be typed)

Monitoring
==========

jsmppgw registers an MBean to a default JMX provider. The bean name
is ```bean:name=SMPPBean```

Monitorable attributes:

    @ManagedAttribute(description="Number of errors since start", currencyTimeLimit=5)
    public long getErrors();

    @ManagedAttribute(description="Last message delivery duration", currencyTimeLimit=5)
    public long getLastDeliveryDuration();

    @ManagedAttribute(description="Was last message delivered?", currencyTimeLimit=5)
    public boolean getLastDeliveryOk();

    @ManagedAttribute(description="The text of last error message", currencyTimeLimit=5)
    public String getLastError();

    @ManagedAttribute(description="Number of messages delivered since start", currencyTimeLimit=5)
    public long getMessagesDelivered();

    @ManagedAttribute(description="Status. Connected and delivering are usual OK states. \"delivering\" for too long is a problem", currencyTimeLimit=5)
    public String getStatus();

    @ManagedAttribute(description="Current delivery duration (0 if not delivering)", currencyTimeLimit=5)
    public long getCurrentDeliveryDuration();

    @ManagedAttribute(description="Number of attempted, but failed redeliveries", currencyTimeLimit=5)
    public int getFailedRedeliveries();

    @ManagedAttribute(description="Number of redelivered messages, that came back and were processed", currencyTimeLimit=5)
    public int getProcessedRedeliveries();

    @ManagedAttribute(description="Number of successful redeliveries back to queue", currencyTimeLimit=5)
    public int getSuccessfulRedeliveries();

    @ManagedAttribute(description = "Number of long messages, that we have split and delivered", currencyTimeLimit = 5)
    public int getProcessedLongMessages();

    @ManagedAttribute(description = "Messages received and delivered to incoming SMS queue", currencyTimeLimit = 5)
    public long getReceivedMessages();

Proper health check should check like this:

 1. status == connected => OK
 2. status == delivering and currentDeliveryDuration < constant (10s?) => OK
 3. => fail. lastError + status provides information

Other things can be measured (number of delivered messages, number
of errors, ...) and graphed.

[Monitoring with Zabbix](http://www.kjkoster.org/zapcat/Jetty_How_To.html): 
Installing Zapcat to zabbix according to [this howto](http://www.kjkoster.org/zapcat/Jetty_How_To.html) creates a zabbix
agent, that can be talked to and all these attributes should be
exposed.

Troubleshooting
===============

During the startup, the broker tries to connect to SMPP gateway.
If that does not happen (and status remain disconnected), no consumer
is registered and no receiving of messages from queues happens. In
addition, SMPPGW does _not_ try to recover from this situation, it
has to be solved manually and container (usually Jetty) restarted.

Good way to check, if it can connect is by opening a connection to
SMPP gateway using telnet. 

Proper use
==========

Sending messages should be done using either ActiveMQ libraries (OpenWire
protocol) or Stomp. Return values and success should be always checked
and ActiveMQ clustering should be used.



