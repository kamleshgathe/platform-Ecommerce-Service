/*
 * Copyright © 2021, JDA Software Group, Inc. ALL RIGHTS RESERVED.
 * <p>
 * This software is the confidential information of JDA Software, Inc., and is licensed
 * as restricted rights software. The use,reproduction, or disclosure of this software
 * is subject to restrictions set forth in your license agreement with JDA.
 */
package com.jda.dct.chatserver.util

import com.jda.dct.chatservice.exception.ChatException
import com.jda.dct.chatservice.utils.ChatRoomUtil
import com.jda.dct.domain.ChatRoom
import com.jda.dct.domain.stateful.Delivery
import com.jda.dct.domain.stateful.PurchaseOrder
import com.jda.dct.domain.stateful.Shipment
import com.jda.luminate.common.objects.Pair
import org.assertj.core.util.Lists
import org.assertj.core.util.Maps
import org.springframework.web.client.ResourceAccessException
import spock.lang.Specification

public class ChatRoomUtilSpec extends Specification {

    def "should create byte array"() {
        given: " initialize object"
        List<Map<String, Object>> objects = Lists.newArrayList()
        objects.add(Maps.newHashMap("key1", "value"));
        when: "converting to byte array"
        ChatRoomUtil.objectToByteArray(objects);
        then: "should not throw any exception"
        noExceptionThrown()
    }

    def "test object from byte array"() {
        given: "Initialize byte array"
        List<Map<String, Object>> objects = Lists.newArrayList()
        objects.add(Maps.newHashMap("key1", "value"));
        byte[] bytes = ChatRoomUtil.objectToByteArray(objects);

        when: "Converting to object"
        Object convertedObject = ChatRoomUtil.byteArrayToObject(bytes);

        then: "Object should match"
        objects == convertedObject
    }

    def "test object from byte should raise IllegalArgumentException exception"() {
        given: "Initialize byte array"

        byte[] bytes = new byte[10];

        when: "Converting to object"
         ChatRoomUtil.byteArrayToObject(bytes);

        then: "Expect exception"
        thrown(ChatException)
    }

    def "test byte array from object should raise IO exception"() {
        given: "Initialize Object"

        List<Object> objects = Mock(List)
        objects.get(_) >> {throw new IOException("Unable to find class")()}
        when: "Converting to byte array"
        ChatRoomUtil.objectToByteArray(objects);

        then: "Expect exception"
        thrown(ChatException)
    }


    def "test object to json raise Chat exception"() {
        given: "Initialize Object"

        List<Object> objects = Mock(List)
        objects.get(_) >> {throw new ChatException(ChatException.ErrorCode.CREATE_SNAPSHOT_ERROR)()}
        when: "Converting to byte array"
        ChatRoomUtil.objectToJson(objects);

        then: "Expect exception"
        thrown(ChatException)
    }

    def "test json to Object "() {
        given: "Initialize Object"
        List<PurchaseOrder> objects = new ArrayList<>()

        objects.add(new PurchaseOrder())

        when: "Converting to byte array"
        ChatRoomUtil.jsonToObject(ChatRoomUtil.objectToJson(objects), PurchaseOrder);

        then: "Expect exception"
        noExceptionThrown()
    }

    def "test json to Object exceptiomn"() {
        given: "Initialize Object"
        List<String> objects = new ArrayList<>()

        objects.add("json1")

        when: "Converting to byte array"
        ChatRoomUtil.jsonToObject(ChatRoomUtil.objectToJson(objects), PurchaseOrder);

        then: "Expect exception"
        thrown(ChatException)
    }

    def "test getModelAndSubtype"() {
        given:
        Delivery delivery = new Delivery();
        delivery.setDeliveryType("inboundDelivery")
        Shipment shipment = new Shipment()
        shipment.setShipmentType("inboundShipment")
        PurchaseOrder po = new PurchaseOrder()
        po.setPurchaseOrderType("standardPO")
        when:
        Pair<String,String> pair = ChatRoomUtil.getModelAndSubtype(delivery);
        Pair<String,String> pairS = ChatRoomUtil.getModelAndSubtype(shipment);
        Pair<String,String> pairP = ChatRoomUtil.getModelAndSubtype(po);
        then:
        pair != null && pair.getFirst() == 'delivery' && pair.getSecond() == 'inboundDelivery'
        pairS != null && pairS.getFirst() == 'shipment' && pairS.getSecond() == 'inboundShipment'
        pairP != null && pairP.getFirst() == 'purchaseOrder' && pairP.getSecond() == 'standardPO'
    }
}
