#!/bin/sh

# Usage: showStatus.sh host1 host2

for host in $*
do
 echo Displaying status information for $host
 for parameter in Status LastError CurrentDeliveryDuration Errors LastDeliveryDuration LastDeliveryOk MessagesDelivered ReceivedMessages SuccessfulRedeliveries FailedRedeliveries ProcessedRedeliveries ProcessedLongMessages
 do
  java -jar cmdline-jmxclient-0.10.3.jar - ${host}:1099 bean:name=SMPPBean $parameter | sed -e 's/org.archive.jmx.Client //'
 done
 echo
done
