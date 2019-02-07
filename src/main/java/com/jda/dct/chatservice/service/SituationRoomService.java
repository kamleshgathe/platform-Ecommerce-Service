/**
 * Copyright © 2019, JDA Software Group, Inc. ALL RIGHTS RESERVED.
 * <p>
 * This software is the confidential information of JDA Software, Inc., and is licensed
 * as restricted rights software. The use,reproduction, or disclosure of this software
 * is subject to restrictions set forth in your license agreement with JDA.
 */

package com.jda.dct.chatservice.service;

import com.jda.dct.chatservice.dto.upstream.AddUserToRoomDto;
import com.jda.dct.chatservice.dto.upstream.ChatContext;
import com.jda.dct.chatservice.dto.upstream.ChatRoomCreateDto;
import com.jda.dct.chatservice.dto.upstream.TokenDto;
import java.util.List;
import java.util.Map;

public interface SituationRoomService {
    TokenDto getSessionToken();

    List<ChatContext> getChannels(String type);

    ChatContext getChannelContext(String channelId);

    Map<String, Object> createChannel(ChatRoomCreateDto request);

    Map<String, Object> inviteUsers(String channel, AddUserToRoomDto users);

    Map<String, Object> postMessage(Map<String, Object> chat);

    Map<String, Object> acceptInvitation(String channelId);

    List<Map<String,Object>> getUnreadCount();
}
