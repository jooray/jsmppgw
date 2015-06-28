/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.digmia.jsmppgw;

/**
 *
 * @author jurajbednar
 */
public class ShortMessage {
    public static final short SENDER_TYPE_LONG = 0;
    public static final short SENDER_TYPE_SHORT = 1;
    public static final short SENDER_TYPE_ALPHANUMERIC = 2;


    private String senderNumber; // ignored when sending
    private String destinationNumber;
    private String textContent;
    private short fromType = SENDER_TYPE_LONG; // ignored when receiving
    private int retransmits = 0;

    private boolean sarFormattedMessage = false;
    private short sarMsgRefNum = 0;
    private byte sarSegmentNumber = 0;
    private byte sarTotalSegments = 1;

    public String getDestinationNumber() {
        return destinationNumber;
    }

    public void setDestinationNumber(String destinationNumber) {
        this.destinationNumber = destinationNumber;
    }

    public short getFromType() {
        return fromType;
    }

    public void setFromType(short fromType) {
        this.fromType = fromType;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public int getRetransmits() {
        return retransmits;
    }

    public void setRetransmits(int retransmits) {
        this.retransmits = retransmits;
    }

    public boolean isSarFormattedMessage() {
        return sarFormattedMessage;
    }

    public void setSarFormattedMessage(boolean sarFormattedMessage) {
        this.sarFormattedMessage = sarFormattedMessage;
    }

    public short getSarMsgRefNum() {
        return sarMsgRefNum;
    }

    public void setSarMsgRefNum(short sarMsgRefNum) {
        this.sarMsgRefNum = sarMsgRefNum;
    }

    public byte getSarSegmentNumber() {
        return sarSegmentNumber;
    }

    public void setSarSegmentNumber(byte sarSegmentNumber) {
        this.sarSegmentNumber = sarSegmentNumber;
    }

    public byte getSarTotalSegments() {
        return sarTotalSegments;
    }

    public void setSarTotalSegments(byte sarTotalSegments) {
        this.sarTotalSegments = sarTotalSegments;
    }

    public String getSenderNumber() {
        return senderNumber;
    }

    public void setSenderNumber(String senderNumber) {
        this.senderNumber = senderNumber;
    }

    
    
    
}
