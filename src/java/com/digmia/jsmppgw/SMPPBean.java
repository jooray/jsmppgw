package com.digmia.jsmppgw;

import java.io.IOException;
import java.util.Date;
import java.util.Enumeration;
import java.util.Random;
import java.util.TimeZone;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.jsmpp.bean.AlertNotification;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.session.DataSmResult;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.jsmpp.bean.Alphabet;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.GeneralDataCoding;
import org.jsmpp.bean.MessageClass;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.SMSCDeliveryReceipt;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.util.AbsoluteTimeFormatter;
import org.jsmpp.util.TimeFormatter;
import javax.jms.*;
import net.sf.json.JSONObject;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.PDUException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.DeliveryReceipt;
import org.jsmpp.bean.MessageType;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.OptionalParameters;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.util.InvalidDeliveryReceiptException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.SessionAwareMessageListener;

// javabean junk
/**
 *
 * @author jurajbednar
 */
@ManagedResource(objectName = "bean:name=SMPPBean", description = "Core SMPP gateway bean", log = true,
logFile = "jmx.log", currencyTimeLimit = 15)
public class SMPPBean implements SessionAwareMessageListener, MessageReceiverListener {

    // configuration options
    //private static final String hostname="";
    private String hostname;
    private int port;
    private String username;
    private String password;
    private String longNumber;
    private String alphanumericNumber;
    private AbstractMessageListenerContainer messageListenerContainer;
    private int maxRetransmits = 50;
    private Destination jmsDestination;
    private Destination incomingQueue;
    private JmsTemplate jmsSenderTemplate;
    private long smppConnectTimeout = 60000L;
    private long messageRetryRate = 1000L;
    private long messageSendingRate = 1000L;
    // for mbeans
    private String status = "uninitialized";
    private String lastError = "";
    private long lastDeliveryDuration = 0L;
    private long messagesDelivered = 0L;
    private long errors = 0L;
    private int lastTries = 0;
    private boolean lastDeliveryOk = true;
    private int successfulRedeliveries = 0;
    private int failedRedeliveries = 0;
    private int processedRedeliveries = 0;
    private long receivedMessages = 0L;
    private int processedLongMessages = 0;

    private Date deliveryStart;
    private SMPPSession session = new SMPPSession();
    private final TimeFormatter timeFormatter = new AbsoluteTimeFormatter();
    private final TimeZone tz = TimeZone.getDefault();
    private final Random random = new Random();

    public SMPPBean() {
    }

    @PostConstruct
    public void initializeSmpp() {
        random.setSeed(new Date().getTime());


        System.out.println("jsmppGw: Initializing SMPP"); // DEBUG
        if (session == null) {
            session = new SMPPSession();
        }


        try {
            session.connectAndBind(hostname, port, new BindParameter(BindType.BIND_TRX, username, password, "cp", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null), smppConnectTimeout);
            session.setMessageReceiverListener(this);

            status = "connected";
            System.out.println("jsmppGw: Connected"); // DEBUG
            messageListenerContainer.start();
            System.out.println("jsmppGw: Message listener started"); // DEBUG
        } catch (IOException e) {
            e.printStackTrace();

            handleException(e, "Failed to connect and bind to host");
        }

    }

    @PreDestroy
    public void destroySmpp() {
        System.out.println("jsmppGw: Disconnecting"); // DEBUG
        session.unbindAndClose();
        session = null;
        status = "disconnected";
        System.out.println("jsmppGw: Disconnected"); // DEBUG
    }

    public void reconnect() {
        destroySmpp();
        session = new SMPPSession();
        initializeSmpp();
    }

    public synchronized void onMessage(Message message, Session jmsSession) {
        deliveryStart = new Date();
        System.out.println("jsmppGw: Entering onMessage"); // DEBUG
        if (session == null) {
            initializeSmpp();
        }
        if (message instanceof TextMessage) {
            String jmsText = null;
            try {
                jmsText = ((TextMessage) message).getText();

                int redeliveries = 0;
                Enumeration propertyNames = message.getPropertyNames();

                while (propertyNames.hasMoreElements()) {
                    final String n = (String) propertyNames.nextElement();
                    if (n.equals("redeliveries")) {
                        redeliveries = message.getIntProperty("redeliveries");
                    }
                }

                if (redeliveries > 0) {
                    processedRedeliveries++;
                    Logger.getLogger("jms").log(Level.INFO,
                            "Trying to redeliver message that came back to queue, redelivery attempt #" + redeliveries);

                }
            } catch (JMSException e) {
                handleException(e, "Unable to read or process message: ");
                System.out.println("jsmppGw: Leaving onMessage: Unable to process message"); // DEBUG
                throw new RuntimeException("Unable to read or process message: " + e.getMessage());
            }

            if (jmsText == null) {
                System.out.println("jsmppGw: Leaving onMessage: jmsText is null"); // DEBUG
                return;
            }

            JSONObject jsonObject = JSONObject.fromObject(jmsText);
            ShortMessage msg = (ShortMessage) JSONObject.toBean(jsonObject,
                    ShortMessage.class);

            if ((msg.getDestinationNumber() == null) ||
                    (msg.getDestinationNumber().length() == 0) ||
                    (msg.getTextContent() == null) ||
                    (msg.getTextContent().length() == 0)) {
                System.out.println("jsmppGw: Either text or destination number is empty"); // DEBUG
                return;
            }

            status = "delivering";


            final long now = new Date().getTime();

            TypeOfNumber typeOfNumber = TypeOfNumber.INTERNATIONAL;
            NumberingPlanIndicator numberingPlanIndicator = NumberingPlanIndicator.ISDN;
            String sourceNumber = longNumber;

            switch (msg.getFromType()) {
                case ShortMessage.SENDER_TYPE_LONG:
                    typeOfNumber = TypeOfNumber.INTERNATIONAL;
                    numberingPlanIndicator = NumberingPlanIndicator.ISDN;
                    sourceNumber = longNumber;
                    break;
                case ShortMessage.SENDER_TYPE_ALPHANUMERIC:
                    typeOfNumber = TypeOfNumber.ALPHANUMERIC;
                    numberingPlanIndicator = NumberingPlanIndicator.UNKNOWN;
                    sourceNumber = alphanumericNumber;
                    break;
                case ShortMessage.SENDER_TYPE_SHORT:
                    //typeOfNumber = TypeOfNumber.NETWORK_SPECIFIC;
                    //numberingPlanIndicator = NumberingPlanIndicator.PRIVATE;
                    //sourceNumber = ;
                    handleException(new RuntimeException("Can not handle short numbers now, T-mobile network hates me"));
            }

            boolean delivered = false;
            Exception lastException = null;
            int tries = 0;
            for (tries = 0; (tries < 10) && (!delivered); tries++) {
                try {
                    try {
                        if (tries > 0) {
                            System.out.println("jsmppGw: Retrying. Try #" + tries); // DEBUG
                            try {
                                Thread.sleep(tries * messageRetryRate);
                            } catch (InterruptedException e) {
                            }

                        }

                        System.out.println("jsmppGw: Submitting message"); // DEBUG

                        if (msg.isSarFormattedMessage()) {
                            // Long message delivery
                            final OptionalParameter sarMsgRefNum = OptionalParameters.newSarMsgRefNum(msg.getSarMsgRefNum());
                            final OptionalParameter sarTotalSegments = OptionalParameters.newSarTotalSegments(msg.getSarTotalSegments());
                            final OptionalParameter sarSegmentSeqnum = OptionalParameters.newSarSegmentSeqnum(msg.getSarSegmentNumber());
                            String messageId = session.submitShortMessage("",
                                    // sender
                                    typeOfNumber,
                                    numberingPlanIndicator,
                                    sourceNumber,
                                    // recipient
                                    TypeOfNumber.INTERNATIONAL,
                                    NumberingPlanIndicator.ISDN,
                                    msg.getDestinationNumber(),
                                    // parameters
                                    new ESMClass(), (byte) 0, (byte) 1,
                                    timeFormatter.format(new Date(now - tz.getOffset(now))),
                                    null, new RegisteredDelivery(SMSCDeliveryReceipt.DEFAULT),
                                    (byte) 0, new GeneralDataCoding(false, false, MessageClass.CLASS1, Alphabet.ALPHA_DEFAULT), (byte) 0,
                                    // message text
                                    msg.getTextContent().getBytes(),
                                    // long message parameters
                                    sarMsgRefNum, sarSegmentSeqnum, sarTotalSegments);
                        } else {
                            if (msg.getTextContent().length() > 160) {
                                splitAndDeliverLongMessage(msg);
                            } else {
                                // Normal message delivery
                                String messageId = session.submitShortMessage("",
                                        // sender
                                        typeOfNumber,
                                        numberingPlanIndicator,
                                        sourceNumber,
                                        // recipient
                                        TypeOfNumber.INTERNATIONAL,
                                        NumberingPlanIndicator.ISDN,
                                        msg.getDestinationNumber(),
                                        // parameters
                                        new ESMClass(), (byte) 0, (byte) 1,
                                        timeFormatter.format(new Date(now - tz.getOffset(now))),
                                        null, new RegisteredDelivery(SMSCDeliveryReceipt.DEFAULT),
                                        (byte) 0, new GeneralDataCoding(false, false, MessageClass.CLASS1, Alphabet.ALPHA_DEFAULT), (byte) 0,
                                        // message text
                                        msg.getTextContent().getBytes());
                            }
                        }

                        message.acknowledge();
                        delivered = true;
                        System.out.println("jsmppGw: Message delivered"); // DEBUG
                        break;
                    } catch (ResponseTimeoutException e) {
                        handleException(e);
                        System.out.println("Caught exception delivering message, reconnecting and retrying " + e.getMessage());
                        reconnect();
                    } catch (IOException e) {
                        handleException(e);
                        System.out.println("Caught IO exception delivering message, reconnecting and retrying " + e.getMessage());
                        reconnect();
                    } catch (PDUException e) {
                        handleException(e);
                        message.acknowledge();
                        System.out.println("Caught exception delivering message, _NOT_ retrying, message has wrong format: " + e.getMessage());
                        return;
                    } catch (InvalidResponseException e) {
                        handleException(e);
                        message.acknowledge();
                        System.out.println("Caught exception delivering message, _NOT_ retrying, reply from operator has wrong format: " + e.getMessage());
                        return;
                    } catch (NegativeResponseException e) {
                        switch (e.getCommandStatus()) {
                            case SMPPConstant.STAT_ESME_RTHROTTLED:
                                System.out.println("We are sending too fast and are throttled. Retrying after waiting for a while");
                                break;
                            case SMPPConstant.STAT_ESME_RINVDSTADR:
                                System.out.println("Invalid destination address, we can not deliver this, not retrying.");
                                errors++;
                                message.acknowledge();
                                return;
                            default:
                                handleException(e, "Got negative response, please check it in SMPP constants (STAT_ESME_...) " + e.getCommandStatus() + ", retrying, error status: " + e.getMessage());
                                break;
                        }
                    }
                } catch (JMSException e) {
                    handleException(e);
                }
            }
            lastTries = tries;
            if (delivered == false) {
                handleException(lastException, "Unable to deliver message, tried for three times.");
                System.out.println("jsmppGw: Leaving onMessage: redelivering message"); // DEBUG
                redeliver((TextMessage) message, jmsSession);
                return;
            } else {
                messagesDelivered++;
                lastDeliveryOk = true;
                status = "connected";
                countDeliveryTime();

            }

        } else {
            handleException(new RuntimeException("Unknown message type"));
        }

        System.out.println("jsmppGw: Leaving onMessage: at the end of routine, everything went OK."); // DEBUG
        try {
            Thread.sleep(messageSendingRate);
        } catch (InterruptedException e) {
        }

    }

    private void handleException(Exception e, String s) {

        lastError = (s == null ? "" : (s + " ")) + e.getMessage() +
                " at " + new Date().toString();
        status = "exception thrown";
        lastDeliveryOk = false;
        errors++;
        countDeliveryTime();
        System.out.println("jsmppGw: Exception thrown: " + lastError);
    }

    private void handleException(Exception e) {
        handleException(e, "Unable to deliver message.");
    }

    private void countDeliveryTime() {
        lastDeliveryDuration = new Date().getTime() - deliveryStart.getTime();
    }

    private void redeliver(TextMessage message, Session jmsSession) {
        try {

            System.out.println("jsmppGw: Redeliver"); // DEBUG

            Enumeration propertyNames = message.getPropertyNames();
            int redeliveries = 0;

            while (propertyNames.hasMoreElements()) {
                final String n = (String) propertyNames.nextElement();
                if (n.equals("redeliveries")) {
                    redeliveries = message.getIntProperty("redeliveries");
                }
            }

            System.out.println("jsmppGw: Redeliveries so far: " + redeliveries); // DEBUG

            if (redeliveries > maxRetransmits) {
                lastError = "MaxRetransmits reached. Last error was: " + lastError;
                System.out.println(lastError); // DEBUG
                throw new RuntimeException(lastError);
            }

            final MessageProducer producer = jmsSession.createProducer(jmsDestination);
            final TextMessage newMsg = jmsSession.createTextMessage();
            newMsg.setText(message.getText());
            newMsg.setIntProperty("redeliveries", redeliveries + 1);
            producer.send(newMsg);
            successfulRedeliveries++;
            System.out.println("jsmppGw: Redelivery OK"); // DEBUG

        } catch (JMSException ex) {
            lastError = "Unable to redeliver message, error: " + ex.getMessage() +
                    " original error that caused redelivery was: " + lastError;
            System.out.println(lastError); // DEBUG
            failedRedeliveries++;
            try {
                jmsSession.recover();
            } catch (JMSException e) {
                Logger.getLogger("jms").log(Level.WARNING, "Unable to recover session", e);
            }
            throw new RuntimeException(lastError);
        }
        System.out.println("jsmppGw: Leaving redeliver()"); // DEBUG
    }

    private void splitAndDeliverLongMessage(ShortMessage msg) {
        processedLongMessages++;
        System.out.println("Splitting long message, has " + msg.getTextContent().length() + " characters"); // DEBUG
        String text = msg.getTextContent();
        short refNum = (short) random.nextInt();
        byte totalSegments = (byte) ((text.length() / 152) + 1);
        if (((text.length() / 152) * 152) == text.length())
            totalSegments--;

        byte segmentNumber = 0;

        while ((text != null) && (text.length() > 0)) {
            segmentNumber++;
            ShortMessage tmpMsg = new ShortMessage();
            tmpMsg.setDestinationNumber(msg.getDestinationNumber());
            tmpMsg.setFromType(msg.getFromType());
            tmpMsg.setSarFormattedMessage(true);
            tmpMsg.setSarMsgRefNum(refNum);
            tmpMsg.setSarSegmentNumber(segmentNumber);
            tmpMsg.setSarTotalSegments(totalSegments);
            if (text.length() > 152)
                tmpMsg.setTextContent(text.substring(0, 152));
            else tmpMsg.setTextContent(text);
            final String textRepresentation = JSONObject.fromObject( tmpMsg ).toString();
            // Sending to queue
            jmsSenderTemplate.send(jmsDestination, new MessageCreator() {

                public Message createMessage(Session session) throws JMSException {
                    return session.createTextMessage(textRepresentation);
                }
                
            });

            if (text.length()>152) {
                text = text.substring(152, text.length());
            } else {
                text = null;
                break;
            }
        }
    }

    // Receiving of messages
    public void onAcceptDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
        if (MessageType.SMSC_DEL_RECEIPT.containedIn(deliverSm.getEsmClass())) {
            try {
                DeliveryReceipt delReceipt = deliverSm.getShortMessageAsDeliveryReceipt();

                // lets convert the id to hex string format
                long id = Long.parseLong(delReceipt.getId()) & 0xffffffff;
                String messageId = Long.toString(id, 16).toUpperCase();
                System.out.println("Received delivery report for msg id: " + messageId +
                        " ' from " + deliverSm.getSourceAddr() + " to " + deliverSm.getDestAddress() + " : " + delReceipt); // DEBUG
            } catch (InvalidDeliveryReceiptException e) {
                System.out.println("Invalid delivery receipt: " + e.getMessage());
            }
        } else {
            final ShortMessage msg = new ShortMessage();
            msg.setTextContent(new String(deliverSm.getShortMessage()));
            msg.setSenderNumber(deliverSm.getSourceAddr());
            msg.setDestinationNumber(deliverSm.getDestAddress());

            // does not work for now
            for (OptionalParameter o : deliverSm.getOptionalParametes()) {
                if (o.tag == OptionalParameter.Tag.SAR_MSG_REF_NUM.code()) {
                    msg.setSarMsgRefNum(
                            ((OptionalParameter.Short) o).getValue());
                } else if (o.tag == OptionalParameter.Tag.SAR_TOTAL_SEGMENTS.code()) {
                    msg.setSarTotalSegments(((OptionalParameter.Byte) o).getValue());
                    if (msg.getSarTotalSegments() > 1) {
                        msg.setSarFormattedMessage(true);
                    }
                } else if (o.tag == OptionalParameter.Tag.SAR_SEGMENT_SEQNUM.code()) {
                    msg.setSarSegmentNumber(((OptionalParameter.Byte) o).getValue());
                }

            final String textRepresentation = JSONObject.fromObject( msg ).toString();

            // Sending to queue
            jmsSenderTemplate.send(incomingQueue, new MessageCreator() {

                public Message createMessage(Session session) throws JMSException {
                    return session.createTextMessage(textRepresentation);
                }

            });

            receivedMessages++;

            }
            System.out.println("Received message from: " + msg.getSenderNumber() +
                    " to: " + msg.getDestinationNumber() + " with text: " + msg.getTextContent());
            if (msg.isSarFormattedMessage()) {
                System.out.println("This was message with SAR ID " + msg.getSarMsgRefNum() + " #" +
                        msg.getSarSegmentNumber() + "/" + msg.getSarTotalSegments());
            }


        }
    }

// javabean junk
    public String getAlphanumericNumber() {
        return alphanumericNumber;
    }

    public void setAlphanumericNumber(String alphanumericNumber) {
        this.alphanumericNumber = alphanumericNumber;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getLongNumber() {
        return longNumber;
    }

    public void setLongNumber(String longNumber) {
        this.longNumber = longNumber;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setMessageListenerContainer(AbstractMessageListenerContainer messageListenerContainer) {
        this.messageListenerContainer = messageListenerContainer;
    }

    public int getMaxRetransmits() {
        return maxRetransmits;
    }

    public void setMaxRetransmits(int maxRetransmits) {
        this.maxRetransmits = maxRetransmits;
    }

    public Destination getJmsDestination() {
        return jmsDestination;
    }

    public void setJmsDestination(Destination jmsDestination) {
        this.jmsDestination = jmsDestination;
    }

    public long getSmppConnectTimeout() {
        return smppConnectTimeout;
    }

    public void setSmppConnectTimeout(long smppConnectTimeout) {
        this.smppConnectTimeout = smppConnectTimeout;
    }

    public long getMessageRetryRate() {
        return messageRetryRate;
    }

    public void setMessageRetryRate(long messageRetryRate) {
        this.messageRetryRate = messageRetryRate;
    }

    public long getMessageSendingRate() {
        return messageSendingRate;
    }

    public void setMessageSendingRate(long messageSendingRate) {
        this.messageSendingRate = messageSendingRate;
    }

    public Destination getIncomingQueue() {
        return incomingQueue;
    }

    public void setIncomingQueue(Destination incomingQueue) {
        this.incomingQueue = incomingQueue;
    }

    public JmsTemplate getJmsSenderTemplate() {
        return jmsSenderTemplate;
    }

    public void setJmsSenderTemplate(JmsTemplate jmsSenderTemplate) {
        this.jmsSenderTemplate = jmsSenderTemplate;
    }

    

// mbean
    @ManagedAttribute(description = "Number of errors since start", currencyTimeLimit = 5)
    public long getErrors() {
        return errors;
    }

    @ManagedAttribute(description = "Last message delivery duration", currencyTimeLimit = 5)
    public long getLastDeliveryDuration() {
        return lastDeliveryDuration;
    }

    @ManagedAttribute(description = "Was last message delivered?", currencyTimeLimit = 5)
    public boolean getLastDeliveryOk() {
        return lastDeliveryOk;
    }

    @ManagedAttribute(description = "The text of last error message", currencyTimeLimit = 5)
    public String getLastError() {
        return lastError;
    }

    @ManagedAttribute(description = "Number of messages delivered since start", currencyTimeLimit = 5)
    public long getMessagesDelivered() {
        return messagesDelivered;
    }

    @ManagedAttribute(description = "Status. Connected and delivering are usual OK states. \"delivering\" for too long is a problem", currencyTimeLimit = 5)
    public String getStatus() {
        return status;
    }

    @ManagedAttribute(description = "Current delivery duration (0 if not delivering)", currencyTimeLimit = 5)
    public long getCurrentDeliveryDuration() {
        if (!status.equals("delivering")) {
            return 0L;
        } else {
            return new Date().getTime() - deliveryStart.getTime();
        }

    }

    @ManagedAttribute(description = "Number of attempted, but failed redeliveries", currencyTimeLimit = 5)
    public int getFailedRedeliveries() {
        return failedRedeliveries;
    }

    @ManagedAttribute(description = "Number of redelivered messages, that came back and were processed", currencyTimeLimit = 5)
    public int getProcessedRedeliveries() {
        return processedRedeliveries;
    }

    @ManagedAttribute(description = "Number of successful redeliveries back to queue", currencyTimeLimit = 5)
    public int getSuccessfulRedeliveries() {
        return successfulRedeliveries;
    }

    @ManagedAttribute(description = "Number of long messages, that we have split and delivered", currencyTimeLimit = 5)
    public int getProcessedLongMessages() {
        return processedLongMessages;
    }

    @ManagedAttribute(description = "Messages received and delivered to incoming SMS queue", currencyTimeLimit = 5)
    public long getReceivedMessages() {
        return receivedMessages;
    }



    // dummy methods

    public void onAcceptAlertNotification(AlertNotification arg0) {
        // forget that for now
    }

    public DataSmResult onAcceptDataSm(
            DataSm shortMessage, org.jsmpp.session.Session session) throws ProcessRequestException {
        // forget that for now
        return null;
    }


}
