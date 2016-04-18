# One-Ping-app-ONOS-bootcamp-group2
The One Ping app is based on ONOS. Created by the ONOS Bootcamp Group2
on 2016/4/18. Group member: Janon Wang, Ran Pang，YuBo Mu, Denise.

Before you can use the one ping function, you need to make a little
change to the ONOS fwd module: Location:
onos/apps/fwd/src/main/java/org/onosproject/fwd/ReactiveForwarding.java
line192 Change "packetService.addProcessor(processor,
PacketProcessor.director(2));" to "packetService.addProcessor(processor,
PacketProcessor.director(3));"
