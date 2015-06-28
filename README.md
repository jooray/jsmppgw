**JSMPPGW** is an open-source **JMS/ActiveMQ** <-> **SMPP** gateway. 

The basic usecase: your SMS operator wants you to send and receive SMS messages (usually in bulk) through SMPP, but you want simpler and more standard way to do it.

**Features**

 * Supports active/active clustering out of the box (underlying JMS, such as ActiveMQ, has to be in active-active mode too).
 * Graceful degradation and retrying of unsent messages.
 * Can be run on any container (Jetty and Glassfish were tested).
 * Is open-source (Apache License 2.0)
 * Is manageable through Java Management Interface. Monitoring, statistics and error reporting available.
 * Tested in production
 * Supports sending of long messages
 * Supports receiving of messages
 
It uses jsmpp library, Apache Commons and command line jmx client for status view.