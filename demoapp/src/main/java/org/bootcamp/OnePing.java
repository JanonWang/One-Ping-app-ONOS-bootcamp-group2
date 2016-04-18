/*
 * Copyright 2016 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bootcamp;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;


/**
 *created by onos bootcamp group 2.
 */
@Component(immediate = true)
public class OnePing {

    private static Logger log = LoggerFactory.getLogger(OnePing.class);

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    private ApplicationId appId;

    private PacketProcessor packetProcessor = new PingProcess();

    private static final int TIMEOUT = 60;
    private static final int PROCESS_PRIORITY = PacketProcessor.director(2);
    private static final int PACKET_IN_PRIORITY = 10;
    private static final int PACKET_DROP_PRIORITY = 12;

    @Activate
    public void activate() {
        appId = coreService.registerApplication("org.bootcamp.OnePing");
        /* add a process whose PROCESS_PRIORITY is higher than FWD module.
         * I just edit the FWD module PROCESS_PRIORITY to 3
         */
        packetService.addProcessor(packetProcessor, PROCESS_PRIORITY);
        // to make sure that all the ping packet will be sent to the controller
        installInitRule();
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        packetService.removeProcessor(packetProcessor);
        flowRuleService.removeFlowRulesById(appId);
        log.info("Stopped");
    }

    private class PingProcess implements PacketProcessor {
        @Override
        public void process(PacketContext packetContext) {
            Ethernet ethnet = packetContext.inPacket().parsed();
            //filtering the ICMP packet
            if (isIcmp(ethnet)) {
                //get the MacPair and de deviceId
                MacAddress srcMac = ethnet.getSourceMAC();
                MacAddress dstMac = ethnet.getDestinationMAC();
                DeviceId deviceId = packetContext.inPacket().receivedFrom().deviceId();
                installDropRules(srcMac, dstMac, deviceId);
                /* create a Timer, after 60s, some rules will be installed.
                 * the installed rules will cover the drop rules
                 */
                Timer timer1 = new Timer();
                timer1.schedule(new CoverTimer(srcMac, dstMac, deviceId), TIMEOUT * 1000);
            }

        }

    }

    /**
     * To judge if it is an ICMP packets.
     * @param ethnet the Ethernet frame
     * @return whether it is an ICMP packet
     */
    private boolean isIcmp(Ethernet ethnet) {
        return ethnet.getEtherType() == Ethernet.TYPE_IPV4 &&
                ((IPv4) ethnet.getPayload()).getProtocol() == IPv4.PROTOCOL_ICMP;
    }

    /**
     * Install a Drop rules on the specific device with macPair.
     * @param srcMac source MacAddress
     * @param dstMac destination MacAddress
     * @param deviceId device ID
     */
    private  void installDropRules(MacAddress srcMac, MacAddress dstMac, DeviceId deviceId) {
                TrafficSelector trafficSelector = DefaultTrafficSelector.builder()
                        .matchEthSrc(srcMac)
                        .matchEthDst(dstMac)
                        .build();
                TrafficTreatment trafficTreatment = DefaultTrafficTreatment.builder()
                        .drop().build();
                flowObjectiveService.forward(deviceId, DefaultForwardingObjective.builder()
                        .withPriority(PACKET_DROP_PRIORITY)
                        .withFlag(ForwardingObjective.Flag.VERSATILE)
                        /**make sure in the next 60s, the packet from specific srcMac will.
                         * be drop directly, rather than packet in to occupy the bandwidth
                         */
                        .makeTemporary(TIMEOUT)
                        .fromApp(appId)
                        .withSelector(trafficSelector)
                        .withTreatment(trafficTreatment)
                        .add());
    }

    private class CoverTimer extends TimerTask {
        private MacAddress srcMac;
        private MacAddress dstMac;
        private DeviceId deviceId;
        CoverTimer(MacAddress srcMac, MacAddress dstMac, DeviceId deviceId) {
            this.srcMac = srcMac;
            this.dstMac = dstMac;
            this.deviceId = deviceId;
        }
        @Override
        public void run() {
            installCoverRule(srcMac, dstMac, deviceId);
        }
    }

    /**
     * Init the flow rules at first to control the ICMP packets to transmit to the controller.
     */
    private void installInitRule() {
        TrafficSelector trafficSelector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_ICMP)
                .build();
        TrafficTreatment trafficTreatment = DefaultTrafficTreatment.builder()
                .punt().immediate().build();
        Iterator<Device> devices = deviceService.getAvailableDevices().iterator();
        while (devices.hasNext()) {
            Device thisDevice = devices.next();
            flowObjectiveService.forward(thisDevice.id(), DefaultForwardingObjective.builder()
                    .withPriority(PACKET_IN_PRIORITY)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .makePermanent()
                    .fromApp(appId)
                    .withSelector(trafficSelector)
                    .withTreatment(trafficTreatment)
                    .add());
        }
    }

    /**
     * install the rules to cover the DROP flow rules.
     * @param srcMac source MacAddress
     * @param dstMac destination MacAddress
     * @param deviceId device ID
     */
    private void installCoverRule(MacAddress srcMac, MacAddress dstMac, DeviceId deviceId) {
        TrafficSelector trafficSelector = DefaultTrafficSelector.builder()
                .matchEthSrc(srcMac)
                .matchEthDst(dstMac)
                .build();
        TrafficTreatment trafficTreatment = DefaultTrafficTreatment.builder()
                .punt().immediate().build();

        flowObjectiveService.forward(deviceId, DefaultForwardingObjective.builder()
                .withPriority(PACKET_DROP_PRIORITY)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .makeTemporary(TIMEOUT)
                .fromApp(appId)
                .withSelector(trafficSelector)
                .withTreatment(trafficTreatment)
                .add());
        log.info("6666");
    }
}
