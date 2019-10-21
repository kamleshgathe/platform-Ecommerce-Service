/**
 * Copyright © 2019, JDA Software Group, Inc. ALL RIGHTS RESERVED.
 * <p>
 * This software is the confidential information of JDA Software, Inc., and is licensed
 * as restricted rights software. The use,reproduction, or disclosure of this software
 * is subject to restrictions set forth in your license agreement with JDA.
 */

package com.jda.dct.chatservice.service;

import static com.jda.dct.chatservice.constants.ChatRoomConstants.FILTER_BY_USER;
import static com.jda.dct.chatservice.constants.ChatRoomConstants.MATTERMOST_CHANNELS;
import static com.jda.dct.chatservice.constants.ChatRoomConstants.MATTERMOST_POSTS;
import static com.jda.dct.chatservice.constants.ChatRoomConstants.MATTERMOST_USERS;
import static com.jda.dct.chatservice.constants.ChatRoomConstants.MAX_REMOTE_USERNAME_LENGTH;
import static com.jda.dct.chatservice.utils.ChatRoomUtil.buildUrlString;

import com.google.common.annotations.VisibleForTesting;
import com.jda.dct.chatservice.domainreader.EntityReaderFactory;
import com.jda.dct.chatservice.dto.downstream.AddParticipantDto;
import com.jda.dct.chatservice.dto.downstream.CreateChannelDto;
import com.jda.dct.chatservice.dto.downstream.RemoteUserDto;
import com.jda.dct.chatservice.dto.downstream.RoleDto;
import com.jda.dct.chatservice.dto.downstream.TeamDto;
import com.jda.dct.chatservice.dto.upstream.AddUserToRoomDto;
import com.jda.dct.chatservice.dto.upstream.ChatContext;
import com.jda.dct.chatservice.dto.upstream.ChatRoomCreateDto;
import com.jda.dct.chatservice.dto.upstream.ResolveRoomDto;
import com.jda.dct.chatservice.dto.upstream.TokenDto;
import com.jda.dct.chatservice.exception.ChatException;
import com.jda.dct.chatservice.repository.ChatRoomParticipantRepository;
import com.jda.dct.chatservice.repository.ProxyTokenMappingRepository;
import com.jda.dct.chatservice.repository.SituationRoomRepository;
import com.jda.dct.chatservice.utils.AssertUtil;
import com.jda.dct.chatservice.utils.ChatRoomUtil;
import com.jda.dct.domain.ChatRoom;
import com.jda.dct.domain.ChatRoomParticipant;
import com.jda.dct.domain.ChatRoomParticipantStatus;
import com.jda.dct.domain.ChatRoomResolution;
import com.jda.dct.domain.ChatRoomStatus;
import com.jda.dct.domain.ProxyTokenMapping;
import com.jda.dct.ignitecaches.springimpl.Tenants;
import com.jda.luminate.security.contexts.AuthContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.transaction.Transactional;
import org.assertj.core.util.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;


/**
 * Situation room service implementation.
 */

@Service
public class SituationRoomServiceImpl implements SituationRoomService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SituationRoomServiceImpl.class);

    @Value("${dct.situationRoom.mattermost.host}")
    private String mattermostUrl;

    @Value("${dct.situationRoom.token}")
    private String adminAccessToken;

    @Value("${dct.situationRoom.mattermost.teamId}")
    private String channelTeamId;

    private final AuthContext authContext;
    private final SituationRoomRepository roomRepository;
    private final ProxyTokenMappingRepository tokenRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final EntityReaderFactory entityReaderFactory;
    private final UniqueRoomNameGenerator generator;

    private RestTemplate restTemplate = new RestTemplate();

    /**
     * Mattermost chat service constructor.
     *
     * @param authContext     Auth context.
     * @param roomRepository  Repository for situation room.
     * @param tokenRepository Proxy token repository.
     */
    public SituationRoomServiceImpl(@Autowired AuthContext authContext,
                                    @Autowired SituationRoomRepository roomRepository,
                                    @Autowired ProxyTokenMappingRepository tokenRepository,
                                    @Autowired ChatRoomParticipantRepository participantRepository,
                                    @Autowired UniqueRoomNameGenerator generator,
                                    @Autowired EntityReaderFactory entityReaderFactory) {
        this.authContext = authContext;
        this.roomRepository = roomRepository;
        this.tokenRepository = tokenRepository;
        this.participantRepository = participantRepository;
        this.generator = generator;
        this.entityReaderFactory = entityReaderFactory;
    }

    /**
     * This API is used to get the current loggedin user token id from remote system.
     * If user does not exists into the remote system, it will first create and create token
     * and return to the caller.
     *
     * @return TokenDto contain token and team id information.
     */
    @Override
    public TokenDto getSessionToken() {
        LOGGER.info("Get chat session token for situation room has called for user {}", authContext.getCurrentUser());
        AssertUtil.notNull(authContext.getCurrentUser(), "Current user can't be null");
        setupUser(authContext.getCurrentUser(), channelTeamId);
        ProxyTokenMapping token = getUserTokenMapping(authContext.getCurrentUser());
        AssertUtil.notNull(token, "User should be present but missing in situation room");
        TokenDto tokenDto = new TokenDto();
        tokenDto.setToken(token.getProxyToken());
        tokenDto.setTeamId(channelTeamId);
        LOGGER.info("Returning token for user {}", authContext.getCurrentUser());
        return tokenDto;
    }

    /**
     * This API is used to post message to chat channel.
     *
     * @param chat Map object. Chat client send chat service provider specific input.
     * @return Map object, contains response from remote system.
     */
    @Override
    public Map<String, Object> postMessage(Map<String, Object> chat) {
        String currentUser = authContext.getCurrentUser();
        validatePostMessageRequest(currentUser, chat);
        Map<String, Object> chatCopy = new HashMap<>(chat);
        LOGGER.info("User {} posting message to channel {}", currentUser,
            getRoomIdFromPostMessage(chatCopy));
        ProxyTokenMapping proxyTokenMapping = getUserTokenMapping(authContext.getCurrentUser());
        HttpHeaders headers = getHttpHeader(proxyTokenMapping.getProxyToken());
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(chatCopy, headers);
        HttpEntity<Map> response = restTemplate.exchange(
            getRemoteActionUrl(getMessagePath()),
            HttpMethod.POST,
            requestEntity,
            Map.class);
        LOGGER.info("Message posted successfully into channel {} by user {}",
            getRoomIdFromPostMessage(chatCopy),
            authContext.getCurrentUser());
        archiveMessage(chatCopy);
        return response.getBody();
    }

    /**
     * This API is used to create channel in remote system. If the participants does not exists in remote
     * system, first it setup user then assigned them to channel.
     *
     * @param request ChatRoomCreateDto, it contains required information for creating new channel.
     * @return Map object, containing remote system returned information.
     */
    @Override
    @Transactional
    public Map<String, Object> createChannel(ChatRoomCreateDto request) {
        validateChannelCreationRequest(request);
        LOGGER.info("Going to create new channel {} requested by {} with details {}", request.getName(),
            authContext.getCurrentUser(), request);
        Tenants.setCurrent(authContext.getCurrentTid());
        HttpEntity<Map> response = createRemoteServerChatRoom(request);
        String roomId = roomId(response.getBody());
        createChatRoomInApp(request, roomId);
        setupParticipantsIfNotBefore(request.getParticipants(), channelTeamId);
        addParticipantsToRoom(authContext.getCurrentUser(), roomId);
        return response.getBody();
    }

    /**
     * This API is used to delete channel in remote system.
     * @param roomId to be deleted
     * @return Map object, containing remote system returned information.
     */
    @Override
    @Transactional
    public Map<String, Object> removeChannel(String roomId) {
        String currentUser = authContext.getCurrentUser();
        LOGGER.info("Delete Situation Room {} has been called by user {}", roomId, currentUser);

        validateRemoveRoomRequest(roomId, currentUser);
        removeRoomInApp(roomId);
        Map<String, Object> response = removeRoomInRemote(roomId, currentUser);
        LOGGER.info("Room {} removed by {} successfully", roomId, currentUser);
        response.put("deletedRoomId", roomId);
        return response;
    }

    private void removeRoomInApp(String roomId) {
        LOGGER.debug("Going to Delete chat room having Room Id {} meta information into system", roomId);
        roomRepository.deleteById(roomId);
        LOGGER.debug("Chat room having room Id {} deleted successfully", roomId);
    }

    private Map<String, Object> removeRoomInRemote(String roomId, String currentUser) {
        ProxyTokenMapping proxyTokenMapping = getUserTokenMapping(currentUser);
        HttpHeaders headers = getHttpHeader(proxyTokenMapping.getProxyToken());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<Map<String, Object>> requestEntity
                = new HttpEntity<>(null, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                    getRemoteActionUrl(removeRoomPath(roomId)),
                    HttpMethod.DELETE,
                    requestEntity,
                    Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            LOGGER.error("Error {} while deleting channel {} for user {}",
                    response.getBody(),
                    roomId,
                    currentUser);
            throw new ResourceAccessException(response.getBody() != null
                    ? response.getBody().toString() :
                    "Remote system unknown exception.");
        }

        LOGGER.info("Situation Room having room Id {} deleted from remote successfully", roomId);

        return response.getBody();
    }

    private String removeRoomPath(String roomId) {
        return getChannelPath() + "/" + roomId;
    }

    private void validateRemoveRoomRequest(String roomId, String currentUser) {
        roomIdInputValidation(roomId);
        Optional<ChatRoom> room = getChatRoomById(roomId);

        if (!room.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.ROOM_NOT_EXISTS);
        }
        AssertUtil.isTrue(room.get().getCreatedBy().equals(currentUser), "Room can only be removed by Creator");
    }

    /**
     * This API allow to add request to an existing channel.
     *
     * @param channel channel id.
     * @param request list request in AddUserToRoomDto object.
     * @return Map containing status
     */
    @Override
    @Transactional
    public Map<String, Object> inviteUsers(String channel, AddUserToRoomDto request) {
        validateInviteUsersInputs(channel, authContext.getCurrentUser(), request);
        setupParticipantsIfNotBefore(request.getUsers(), channelTeamId);
        updateParticipantOfRooms(request.getUsers(), channel);
        Map<String, Object> status = new HashMap<>();
        status.put("Status", "Success");
        return status;
    }

    /**
     * This API will return channel context.
     *
     * @param channelId Channel Id
     * @return ChatContext Channel context object
     */
    @Override
    public ChatContext getChannelContext(String channelId) {
        AssertUtil.isTrue(!StringUtils.isEmpty(channelId), "Channel id can't be null or empty");
        LOGGER.info("Going to fetch chat room {} context request by user {}", channelId, authContext.getCurrentUser());
        Optional<ChatRoom> chatRoom = getChatRoomById(channelId);
        if (!chatRoom.isPresent()) {
            LOGGER.error("Chat room {} does not exists", channelId);
            Object[] args = new Object[1];
            args[0] = channelId;
            throw new ChatException(ChatException.ErrorCode.CHANNEL_NOT_EXISTS, channelId);
        }
        LOGGER.info("Returning chat room {} context", channelId);
        return toChatContext(chatRoom.get(), authContext.getCurrentUser());
    }

    @Override
    public List<ChatContext> getChannels(String by, String type) {
        String currentUser = authContext.getCurrentUser();
        List<ChatRoomParticipant> participants;
        if (StringUtils.isEmpty(type)) {
            LOGGER.info("Fetching all chat rooms for user {}", currentUser);
            participants = getUserAllRooms(currentUser);
        } else if (StringUtils.isEmpty(by) || FILTER_BY_USER.equals(by.trim())) {
            LOGGER.info("Fetching {} chat rooms for user {}", type, currentUser);
            participants = getRoomsByParticipantStatus(type, currentUser);
        } else {
            LOGGER.info("Fetching {} chat rooms for user {}", type, currentUser);
            participants = getUserAllRoomsOfType(type, currentUser);
        }
        return participants
            .stream()
            .map(ChatRoomParticipant::getRoom)
            .map(room -> toChatContext(room, currentUser))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public Map<String, Object> acceptInvitation(String roomId) {
        String currentUser = authContext.getCurrentUser();
        AssertUtil.isTrue(!StringUtils.isEmpty(roomId), "Room id can't be null");
        Map<String, Object> response = addParticipantsToRoom(currentUser, roomId);
        LOGGER.info("User {} added successfully to remote room {}", currentUser, roomId);
        return response;
    }

    @Override
    public List<Map<String, Object>> getUnreadCount() {
        String currentUser = authContext.getCurrentUser();
        List<Map<String, Object>> response = new ArrayList<>();
        LOGGER.info("User {} called for unread message count", currentUser);
        ProxyTokenMapping proxyTokenMapping = getUserTokenMapping(currentUser);
        String remoteUserId = proxyTokenMapping.getRemoteUserId();
        List<ChatRoomParticipant> userRooms =
            getRoomsByParticipantStatus(ChatRoomParticipantStatus.JOINED.name(), currentUser);
        LOGGER.info("Fetching total {} number of room unread count for user {} as remote user is {}",
            userRooms.size(),
            currentUser,
            proxyTokenMapping.getRemoteUserId());
        HttpHeaders headers = getHttpHeader(proxyTokenMapping.getProxyToken());
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        for (ChatRoomParticipant userRoom : userRooms) {
            String roomId = userRoom.getRoom().getId();
            try {
                ResponseEntity<Map> remoteResponse = restTemplate.exchange(
                    getRemoteActionUrl(getChannelUnreadCountPath(remoteUserId, roomId)),
                    HttpMethod.GET,
                    new HttpEntity<Map<String, Object>>(headers),
                    Map.class);
                if (remoteResponse.getStatusCode().is2xxSuccessful()) {
                    response.add(remoteResponse.getBody());
                } else {
                    LOGGER.error("Error {} while fetching unread count for channel {} for user {}",
                        remoteResponse.getBody(),
                        roomId,
                        currentUser);
                }

            } catch (Exception t) {
                LOGGER.error("Unable to fetch unread count for channel {} for user {}", roomId, currentUser, t);
            }
        }
        return response;
    }


    @Override
    public ChatContext resolve(String roomId, ResolveRoomDto request) {
        String currentUser = authContext.getCurrentUser();
        LOGGER.info("User {} is resolving room {} with details {}", currentUser, roomId, request);
        validateResolveRoomInputs(roomId, currentUser, request);
        Optional<ChatRoom> record = getChatRoomById(roomId);

        if (!record.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.INVALID_ROOM);
        }
        ChatRoom room = record.get();

        room.setStatus(ChatRoomStatus.RESOLVED);
        room.setResolution(buildResolution(request, currentUser));
        room.setLmd(room.getResolution().getDate());
        ChatRoom resolvedRoom = saveChatRoom(room);
        LOGGER.info("Room {} status has changed to resolved by user {}", roomId, currentUser);
        return toChatContext(resolvedRoom, currentUser);
    }

    @Override
    @Transactional
    public Map<String, Object> removeParticipant(String roomId, String targetUser) {
        String currentUser = authContext.getCurrentUser();
        LOGGER.info("Remove participant {} from room {} has been called by user {}", targetUser, roomId, currentUser);
        validateRemoveUserRequest(roomId, currentUser, targetUser);
        ChatRoomParticipant participant = removeParticipantInApp(roomId, targetUser);
        if (ChatRoomParticipantStatus.JOINED.equals(participant.getStatus())) {
            removeParticipantInRemote(roomId, targetUser, currentUser);
        }
        Map<String, Object> status = new HashMap<>();
        status.put("Status", "Success");
        LOGGER.info("User {} removed participant {} from room {} successfully", currentUser, targetUser, roomId);
        return status;
    }

    private void validateChannelCreationRequest(ChatRoomCreateDto request) {
        AssertUtil.notNull(request, "Room creation input can't be null");
        AssertUtil.notEmpty(request.getObjectIds(), "Reference domain object can't be null or empty");
        AssertUtil.notEmpty(request.getParticipants(), "Participants can't be null");
        AssertUtil.isTrue(!StringUtils.isEmpty(request.getEntityType()), "Entity type can't be null or empty");
        AssertUtil.isTrue(!StringUtils.isEmpty(request.getName()), "Room name can't be null or empty");
        AssertUtil.isTrue(!StringUtils.isEmpty(request.getPurpose()), "Purpose can't be null or empty");
        AssertUtil.isTrue(!StringUtils.isEmpty(request.getSituationType()),
            "Situation type can't be null or empty");
    }

    private void validatePostMessageRequest(String currentUser, Map<String, Object> request) {
        LOGGER.debug("Validating post message request");
        AssertUtil.notNull(request, "Post message can't be null");
        AssertUtil.notEmpty(request, "Post message can't be empty");
        AssertUtil.notNull(getRoomIdFromPostMessage(request),
            "Channel can't be null");
        AssertUtil.isTrue(getRoomIdFromPostMessage(request).trim().length() > 0,
            "Channel can't be empty");
        String roomId = getRoomIdFromPostMessage(request);
        validateRoomState(roomId, currentUser, "Message can't be post to a resolved room");
        Optional<ChatRoom> room = getChatRoomById(roomId);
        if (!room.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.ROOM_NOT_EXISTS);
        }
        boolean present = room.get()
            .getParticipants()
            .stream()
            .anyMatch(p -> p.getUserName().equals(currentUser)
                && p.getStatus().equals(ChatRoomParticipantStatus.PENDING));
        AssertUtil.isTrue(!present,
            String.format("You are not authorize to resolve room %s", room.get().getRoomName()));

    }

    private void validateInviteUsersInputs(String roomId, String currentUser, AddUserToRoomDto request) {
        LOGGER.debug("Validating invite user request");
        roomIdInputValidation(roomId);
        AssertUtil.notNull(request, "Request can't be null");
        AssertUtil.notEmpty(request.getUsers(), "Users can't be empty");
        validateRoomState(roomId, currentUser, "New invitation can't be sent for resolved room");
    }

    private void validateResolveRoomInputs(String roomId, String currentUser, ResolveRoomDto request) {
        LOGGER.debug("Validating resolve room request");
        roomIdInputValidation(roomId);
        AssertUtil.notNull(request.getResolution(),
            "Resolution can't be null");
        AssertUtil.notEmpty(request.getResolution(), "Resolution can't be empty");
        request.getResolution()
            .forEach(resolution -> AssertUtil.isTrue(!StringUtils.isEmpty(resolution), "Invalid resolution type"));
        validateRoomState(roomId, currentUser, "Room is already resolved");
        Optional<ChatRoom> room = getChatRoomById(roomId);
        if (!room.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.ROOM_NOT_EXISTS);
        }
        boolean present = room.get()
            .getParticipants()
            .stream()
            .anyMatch(p -> p.getUserName().equals(currentUser)
                && p.getStatus().equals(ChatRoomParticipantStatus.PENDING));
        AssertUtil.isTrue(!present,
            String.format("You are not authorize to resolve room %s", room.get().getRoomName()));
    }

    private void validateRemoveUserRequest(String roomId, String currentUser, String targetUser) {
        LOGGER.debug("Validating resolve room request");
        roomIdInputValidation(roomId);
        validateRoomState(roomId, currentUser, "Room is already resolved,user can't be removed");
        Optional<ChatRoom> room = getChatRoomById(roomId);

        if (!room.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.ROOM_NOT_EXISTS);
        }
        boolean present = room.get()
            .getParticipants()
            .stream()
            .anyMatch(p -> p.getUserName().equals(currentUser)
                && p.getStatus().equals(ChatRoomParticipantStatus.PENDING));
        AssertUtil.isTrue(!present, String.format("You are not authorize to remove room %s", room.get().getRoomName()));
        AssertUtil.isTrue(!room.get().getCreatedBy().equals(targetUser), "Creator of can't be remove");
    }

    private void roomIdInputValidation(String roomId) {
        AssertUtil.isTrue(!StringUtils.isEmpty(roomId), "Room Id can't be null or empty");
    }

    private void validateRoomState(String roomId,
                                   String currentUser,
                                   String invalidStatusMsg) {
        Optional<ChatRoom> room = getChatRoomById(roomId);
        if (!room.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.INVALID_CHAT_ROOM, roomId);
        }
        AssertUtil.isTrue(room.get().getStatus() != ChatRoomStatus.RESOLVED,
            invalidStatusMsg);
        boolean present = room.get().getParticipants().stream().anyMatch(p -> p.getUserName().equals(currentUser));
        AssertUtil.isTrue(present, String.format("You are not part of %s room", room.get().getRoomName()));
    }

    private HttpEntity<Map> createRemoteServerChatRoom(ChatRoomCreateDto request) {
        LOGGER.info("Creating new situation room {} by user {} requested, with details {}", request.getName(),
            authContext.getCurrentUser(), request);
        ProxyTokenMapping tokenMapping = getUserTokenMapping(authContext.getCurrentUser());
        HttpHeaders headers = getHttpHeader(tokenMapping.getProxyToken());
        HttpEntity<CreateChannelDto> requestEntity
            = new HttpEntity<>(buildRemoteChannelCreationRequest(request), headers);
        HttpEntity<Map> response = restTemplate.exchange(
            getRemoteActionUrl(getChannelPath()),
            HttpMethod.POST,
            requestEntity,
            Map.class);

        LOGGER.info("Channel {} creation done in remote system", request.getName());

        return response;
    }

    private void createChatRoomInApp(ChatRoomCreateDto request, String roomId) {
        ChatRoom chatRoom = buildChatRoom(roomId, request);
        LOGGER.debug("Going to create chat room {} meta information into system", chatRoom.getRoomName());
        saveChatRoom(chatRoom);
        LOGGER.debug("Chat room {} meta information persisted successfully", chatRoom.getRoomName());
    }

    private ChatRoomParticipant removeParticipantInApp(String roomId, String targetUser) {
        Optional<ChatRoom> roomRecord = getChatRoomById(roomId);
        if (!roomRecord.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.INVALID_CHAT_ROOM, roomId);
        }
        ChatRoom room = roomRecord.get();
        Set<ChatRoomParticipant> participants = room.getParticipants();
        Optional<ChatRoomParticipant> targetParticipant = participants
            .stream()
            .filter(p -> p.getUserName().equals(targetUser)).findAny();

        if (!targetParticipant.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.PARTICIPANT_NOT_BELONG);
        }
        room.getParticipants().remove(targetParticipant.get());
        room.setLmd(new Date());
        saveChatRoom(room);
        return targetParticipant.get();
    }

    private void removeParticipantInRemote(String roomId, String targetUser, String currentUser) {
        ProxyTokenMapping callerTokenMapping = getUserTokenMapping(currentUser);
        ProxyTokenMapping targetUserTokenMapping = getUserTokenMapping(targetUser);
        HttpHeaders headers = getHttpHeader(callerTokenMapping.getProxyToken());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<CreateChannelDto> requestEntity
            = new HttpEntity<>(null, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
            getRemoteActionUrl(removeParticipantPath(roomId, targetUserTokenMapping.getRemoteUserId())),
            HttpMethod.DELETE,
            requestEntity,
            Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            LOGGER.error("Error {} while fetching unread count for channel {} for user {}",
                response.getBody(),
                roomId,
                currentUser);
            throw new ResourceAccessException(response.getBody() != null
                ? response.getBody().toString() :
                "Remote system unknown exception.");
        }
    }

    private Optional<ChatRoom> getChatRoomById(String id) {
        LOGGER.debug("Fetching chat room by id {}", id);
        return roomRepository.findById(id);

    }


    private List<ChatRoomParticipant> getUserAllRoomsOfType(String type, String currentUser) {
        List<ChatRoomParticipant> participants;
        participants = participantRepository.findByUserNameAndRoomStatusOrderByRoomLmdDesc(currentUser,
            ChatRoomStatus.valueOf(type));
        return participants;
    }

    private List<ChatRoomParticipant> getUserAllRooms(String currentUser) {
        List<ChatRoomParticipant> participants;
        participants = participantRepository.findByUserNameOrderByRoomLmdDesc(currentUser);
        return participants;
    }

    private List<ChatRoomParticipant> getRoomsByParticipantStatus(String type, String currentUser) {
        List<ChatRoomParticipant> participants;
        participants = participantRepository.findByUserNameAndStatusOrderByRoomLmdDesc(currentUser,
            ChatRoomParticipantStatus.valueOf(type));
        return participants;
    }

    private void setupParticipantsIfNotBefore(List<String> users, String teamId) {
        users.forEach(user -> setupUser(user, teamId));
    }

    private ProxyTokenMapping setupUser(String appUserId, String teamId) {
        LOGGER.info("Checking whether user {} exist in system or not", appUserId);
        ProxyTokenMapping proxyTokenMapping = getUserTokenMapping(appUserId);
        if (proxyTokenMapping == null) {
            LOGGER.warn("User {} does not exists into system", appUserId);
            Map<String, Object> newUserResponse = crateUser(appUserId);
            setupRoles(remoteUserId(newUserResponse));
            proxyTokenMapping = addUserTokenMapping(appUserId, remoteUserId(newUserResponse));
            proxyTokenMapping = setupAccessToken(proxyTokenMapping);
            joinTeam(proxyTokenMapping.getRemoteUserId(), teamId);
            LOGGER.info("Setup done for user {}", appUserId);
        } else {
            LOGGER.info("User {} already present into system", appUserId);
        }
        return proxyTokenMapping;
    }


    private void joinTeam(String remoteUserId, String teamId) {
        LOGGER.info("Adding user {} to team {}", remoteUserId, teamId);
        HttpHeaders headers = getHttpHeader(adminAccessToken);
        HttpEntity<TeamDto> requestEntity
            = new HttpEntity<>(
            buildJoinTeamRequest(remoteUserId, teamId),
            headers);
        HttpEntity<Map> response = restTemplate.exchange(
            getRemoteActionUrl(getTeamsPath(teamId)),
            HttpMethod.POST,
            requestEntity,
            Map.class);
        if (!((ResponseEntity<Map>) response).getStatusCode().is2xxSuccessful()) {
            throw new ChatException(ChatException.ErrorCode.UNABLE_TO_JOIN_ROOM);
        }
        LOGGER.info("User {} added to team {} successfully", remoteUserId, teamId);
    }

    private Map<String, Object> addParticipantsToRoom(String user, String roomId) {
        Optional<ChatRoom> roomRecord = getChatRoomById(roomId);
        if (!roomRecord.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.INVALID_CHAT_ROOM, roomId);
        }
        ChatRoom room = roomRecord.get();

        Optional<ChatRoomParticipant> participantRecord = room.getParticipants()
            .stream().filter(p -> p.getUserName().equals(user)).findFirst();

        if (!participantRecord.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.USER_NOT_INVITED, user, roomId);
        }
        ChatRoomParticipant participant = participantRecord.get();
        if (participant.getStatus() == ChatRoomParticipantStatus.JOINED) {
            LOGGER.info("User {} already joined to room {} not doing anything", user, roomId);
            Map<String, Object> response = new HashMap<>();
            response.put("channel_id", roomId);
            response.put("user_id", user);
            return response;
        }
        Date date = new Date();
        participant.setJoinedAt(date);
        participant.setStatus(ChatRoomParticipantStatus.JOINED);
        room.setLmd(date);
        room.setTotalMessageCount(room.getTotalMessageCount() + 1);
        String creatorToken = getUserTokenMapping(room.getCreatedBy()).getProxyToken();
        String remoteUserId = getUserTokenMapping(user).getRemoteUserId();
        LOGGER.info("Going to add user {} for room {} as remote user id {} into remote system",
            user, roomId, remoteUserId);
        Map response = joinRoom(creatorToken, remoteUserId, roomId);
        saveChatRoom(room);
        LOGGER.info("Room {} last modified updated successfully for new joined {}", roomId, user);
        return (Map<String, Object>) response;
    }

    private Map joinRoom(String callerToken, String remoteUserId, String roomId) {
        HttpHeaders headers = getHttpHeader(callerToken);
        HttpEntity<AddParticipantDto> requestEntity
            = new HttpEntity<>(
            buildChannelMemberRequest(remoteUserId, ""), headers);
        HttpEntity<Map> response = restTemplate.exchange(
            getRemoteActionUrl(getAddParticipantPath(roomId)),
            HttpMethod.POST,
            requestEntity,
            Map.class);
        if (!((ResponseEntity<Map>) response).getStatusCode().is2xxSuccessful()) {
            throw new ChatException(ChatException.ErrorCode.UNABLE_TO_JOIN_ROOM);
        }
        return response.getBody();
    }

    private ProxyTokenMapping setupAccessToken(ProxyTokenMapping mapping) {
        LOGGER.info("Generating access token in remote system for user {} ", mapping.getAppUserId());
        HttpHeaders headers = getHttpHeader(adminAccessToken);
        Map<String, String> request = teamRequest();
        HttpEntity<RemoteUserDto> requestEntity = new HttpEntity(request, headers);
        HttpEntity<Map> response = restTemplate.exchange(
            getRemoteActionUrl(getTokenPath(mapping.getRemoteUserId())),
            HttpMethod.POST,
            requestEntity,
            Map.class);
        if (!((ResponseEntity<Map>) response).getStatusCode().is2xxSuccessful()) {
            throw new ChatException(ChatException.ErrorCode.UNABLE_TO_UPDATE_ROLE);
        }
        LOGGER.info("Access token generated successfully for user {}", mapping.getAppUserId());
        mapping.setProxyToken(token(response.getBody()));
        LOGGER.info("Updating token mapping for user {}", mapping.getAppUserId());
        ProxyTokenMapping updatedMapping = saveProxyTokenMapping(mapping);
        LOGGER.info("Token mapping for user {} updated successfully", mapping.getAppUserId());
        return updatedMapping;
    }


    private Map<String, Object> crateUser(String appUserId) {
        LOGGER.warn("Creating new user{} into system", appUserId);
        RemoteUserDto newUser = buildNewRemoteUser(appUserId);
        HttpHeaders headers = getHttpHeader(adminAccessToken);
        HttpEntity<RemoteUserDto> requestEntity = new HttpEntity<>(newUser, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
            getRemoteActionUrl(getUsersPath()),
            HttpMethod.POST,
            requestEntity,
            Map.class);

        LOGGER.info("User {} created in system and with id {}", appUserId,
            response.getBody().get("id"));

        return response.getBody();
    }

    private void setupRoles(String userId) {
        LOGGER.info("Assigning roles to user {}", userId);
        HttpHeaders headers = getHttpHeader(adminAccessToken);
        RoleDto role = buildRoles();
        HttpEntity<RoleDto> requestEntity = new HttpEntity<>(role, headers);
        HttpEntity<Map> response = restTemplate.exchange(
            getRemoteActionUrl(getRolePath(userId)),
            HttpMethod.PUT,
            requestEntity,
            Map.class);
        if (!((ResponseEntity<Map>) response).getStatusCode().is2xxSuccessful()) {
            throw new ChatException(ChatException.ErrorCode.UNABLE_TO_UPDATE_ROLE);
        }
        LOGGER.info("Roles assigned successfully to user {}", userId);
    }


    private ProxyTokenMapping addUserTokenMapping(String appUser, String remoteUserId) {
        LOGGER.info("Adding remote user id mapping for user {}", appUser);
        ProxyTokenMapping proxyTokenMapping = new ProxyTokenMapping();
        proxyTokenMapping.setAppUserId(appUser);
        proxyTokenMapping.setRemoteUserId(remoteUserId);
        proxyTokenMapping.setProxyToken("not present");
        proxyTokenMapping.setTid(authContext.getCurrentTid());
        Date currentDate = new Date();
        proxyTokenMapping.setCreationDate(currentDate);
        proxyTokenMapping.setLmd(currentDate);
        ProxyTokenMapping mapping = saveProxyTokenMapping(proxyTokenMapping);
        LOGGER.info("Remote user id {} mapping for appuser {} finish successfully", mapping.getRemoteUserId(), appUser);
        return mapping;
    }

    private ProxyTokenMapping getUserTokenMapping(String user) {
        return tokenRepository.findByAppUserId(user);
    }

    private ProxyTokenMapping saveProxyTokenMapping(ProxyTokenMapping proxyTokenMapping) {
        return tokenRepository.save(proxyTokenMapping);
    }

    private void archiveMessage(Map<String, Object> chat) {
        String roomId = getRoomIdFromPostMessage(chat);
        LOGGER.debug("Going to archive conversion for room {}", roomId);
        Optional<ChatRoom> record = getChatRoomById(getRoomIdFromPostMessage(chat));
        if (!record.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.INVALID_CHAT_ROOM, roomId);
        }
        ChatRoom room = record.get();
        List<Object> chats = (List<Object>) ChatRoomUtil.byteArrayToObject(room.getChats());
        chats.add(chat);
        room.setTotalMessageCount(room.getTotalMessageCount() + 1);
        room.setChats(ChatRoomUtil.objectToByteArray(chats));
        Date date = new Date();
        room.setLastPostAt(date);
        room.setLmd(date);
        saveChatRoom(room);
        LOGGER.debug("Chat archived successfully for room {}", getRoomIdFromPostMessage(chat));
    }

    private void updateParticipantOfRooms(List<String> users, String roomId) {
        LOGGER.debug("Going to add new participants for room {}", roomId);
        Optional<ChatRoom> record = getChatRoomById(roomId);
        if (!record.isPresent()) {
            throw new ChatException(ChatException.ErrorCode.INVALID_CHAT_ROOM, roomId);
        }
        ChatRoom room = record.get();
        Set<ChatRoomParticipant> existingUsers = room.getParticipants();
        existingUsers.addAll(buildParticipants(room, users));
        room.setParticipants(existingUsers);
        saveChatRoom(room);
        LOGGER.debug("Participants updated successfully {}", room);
    }

    private ChatRoom saveChatRoom(ChatRoom chatRoom) {
        LOGGER.debug("Going to persist chat room {} meta information into system", chatRoom.getRoomName());
        ChatRoom savedChatRoot = roomRepository.save(chatRoom);
        LOGGER.debug("Chat room {} meta information persisted successfully", chatRoom.getRoomName());
        return savedChatRoot;
    }

    private ChatContext toChatContext(ChatRoom room, String caller) {
        ChatContext context = new ChatContext();
        context.setId(room.getId());
        context.setName(room.getRoomName());
        context.setTeamId(room.getTeamId());
        context.setCreatedBy(room.getCreatedBy());
        context.setEntityType(room.getEntityType());
        context.setRoomStatus(room.getStatus().name());
        if (room.getResolution() != null) {
            ChatRoomResolution resolution = room.getResolution();
            context.setResolution(resolution.getResolution());
            context.setResolutionRemark(resolution.getRemark());
            context.setResolvedBy(resolution.getResolvedBy());
            context.setResolvedAt(resolution.getDate().getTime());
        }

        List<String> channelUsers = new ArrayList<>();
        room.getParticipants().forEach(participant -> {
            if (participant.getUserName().equals(caller)) {
                context.setYourStatus(participant.getStatus());
            }
            channelUsers.add(participant.getUserName());
        });
        context.setTotalMessageCount(room.getTotalMessageCount());
        context.setParticipants(channelUsers);
        context.setPurpose(room.getDescription());
        context.setSituationType(room.getSituationType());
        context.setEntity(ChatRoomUtil.jsonToObject(
            (String) ChatRoomUtil.byteArrayToObject(room.getContexts())));
        context.setCreatedAt(room.getCreationDate().getTime());
        context.setUpdatedAt(room.getLmd().getTime());
        context.setLastPostAt(room.getLastPostAt().getTime());


        context.setDeletedAt(0);
        context.setExpiredAt(0);
        context.setExtraUpdateAt(0);
        return context;
    }

    private ChatRoom buildChatRoom(String id, ChatRoomCreateDto request) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setRoomName(request.getName());
        room.setEntityType(request.getEntityType());
        room.setCreatedBy(authContext.getCurrentUser());
        room.setDescription(request.getPurpose());
        room.setTeamId(channelTeamId);
        room.setStatus(ChatRoomStatus.OPEN);
        room.setResolution(null);
        room.setSituationType(request.getSituationType());
        room.setParticipants(buildParticipantsIncludingCreator(room, request.getParticipants()));
        Date time = new Date();
        room.setCreationDate(time);
        room.setLmd(time);
        room.setLastPostAt(time);
        room.setTotalMessageCount(1);
        room.setTid(authContext.getCurrentTid());
        room.setDomainObjectIds(request.getObjectIds());
        room.setChats(ChatRoomUtil.objectToByteArray(Lists.newArrayList()));
        List<Object> chatEntities = new ArrayList<>();
        for (String entityId : request.getObjectIds()) {
            Object entity = entityReaderFactory.getEntity(request.getEntityType(), entityId);
            chatEntities.add(entity);
        }
        room.setContexts(ChatRoomUtil.objectToByteArray(ChatRoomUtil.objectToJson(chatEntities)));
        return room;
    }

    private Set<ChatRoomParticipant> buildParticipantsIncludingCreator(ChatRoom room, List<String> joinees) {

        ChatRoomParticipant invitee = buildParticipant(room, authContext.getCurrentUser());
        invitee.setJoinedAt(new Date());
        invitee.setStatus(ChatRoomParticipantStatus.JOINED);
        Set<ChatRoomParticipant> participants = buildParticipants(room, joinees);
        if (participants.contains(invitee)) {
            participants.remove(invitee);
            participants.add(invitee);
        }
        return participants;
    }

    private Set<ChatRoomParticipant> buildParticipants(ChatRoom room, List<String> joinees) {
        Set<ChatRoomParticipant> participants = new HashSet<>();
        participants.add(buildParticipant(room, authContext.getCurrentUser()));
        joinees.forEach(user -> participants.add(buildParticipant(room, user)));
        return participants;
    }

    private ChatRoomParticipant buildParticipant(ChatRoom room, String userName) {
        ChatRoomParticipant participant = new ChatRoomParticipant();
        participant.setId(toParticipantId(userName, room.getId()));
        participant.setRoom(room);
        participant.setUserName(userName);
        participant.setInvitedAt(new Date());
        participant.setStatus(ChatRoomParticipantStatus.PENDING);
        return participant;
    }

    private CreateChannelDto buildRemoteChannelCreationRequest(ChatRoomCreateDto request) {
        CreateChannelDto dto = new CreateChannelDto();
        dto.setTeamId(channelTeamId);
        dto.setName(generator.next());
        dto.setHeader(request.getHeader());
        dto.setPurpose(request.getPurpose());
        dto.setRoomType(request.getRoomType());
        return dto;
    }

    private RemoteUserDto buildNewRemoteUser(String username) {
        username = username.replace("@", "");
        username = username.length() < MAX_REMOTE_USERNAME_LENGTH
            ? username : username.substring(0, MAX_REMOTE_USERNAME_LENGTH);
        RemoteUserDto user = new RemoteUserDto();
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setPassword("dummy1234");
        return user;
    }

    private TeamDto buildJoinTeamRequest(String userId, String teamId) {
        TeamDto teamDto = new TeamDto();
        teamDto.setUserId(userId);
        teamDto.setTeamId(teamId);
        return teamDto;
    }

    private AddParticipantDto buildChannelMemberRequest(String userId, String postId) {
        AddParticipantDto dto = new AddParticipantDto();
        dto.setPostRootId(postId);
        dto.setUserId(userId);
        return dto;
    }

    private Map<String, String> teamRequest() {
        Map<String, String> request = new HashMap<>();
        request.put("description", "situation room");
        return request;
    }

    private RoleDto buildRoles() {
        return new RoleDto("team_user channel_admin "
            + "channel_user system_user_access_token");
    }

    private ChatRoomResolution buildResolution(ResolveRoomDto request, String resolveBy) {
        ChatRoomResolution resolution = new ChatRoomResolution();
        resolution.setDate(new Date());
        resolution.setRemark(request.getRemark());
        resolution.setResolvedBy(resolveBy);
        resolution.setResolution(request.getResolution());
        return resolution;
    }

    private String getRemoteActionUrl(String cxtPath) {
        return buildUrlString(mattermostUrl, cxtPath).toString();
    }

    private static String getUsersPath() {
        return MATTERMOST_USERS;
    }

    private static String getMessagePath() {
        return MATTERMOST_POSTS;
    }

    private static String getChannelPath() {
        return MATTERMOST_CHANNELS;
    }

    private static String getAddParticipantPath(String roomId) {
        return getChannelPath() + "/" + roomId + "/members";
    }

    private static String removeParticipantPath(String room, String user) {
        return getAddParticipantPath(room) + "/" + user;
    }

    private static String getTeamsPath(String teamId) {
        return "/teams/" + teamId + "/members";
    }

    private static String getRolePath(String userId) {
        return getUsersPath() + "/" + userId + "/roles";
    }

    private static String getTokenPath(String userId) {
        return getUsersPath() + "/" + userId + "/tokens";
    }

    private static String getChannelUnreadCountPath(String userId, String channelId) {
        return getUsersPath() + "/" + userId + "/" + getChannelPath() + "/" + channelId + "/unread";
    }

    private static String roomId(Map<String, Object> input) {
        return (String) input.get("id");
    }

    private static String remoteUserId(Map<String, Object> input) {
        return (String) input.get("id");
    }

    private String token(Map body) {
        return (String) body.get("token");
    }

    private String getRoomIdFromPostMessage(Map<String, Object> request) {
        return (String) request.get("channel_id");
    }

    private String toParticipantId(String userName, String roomId) {
        return userName + "-" + roomId;
    }

    private HttpHeaders getHttpHeader(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, HttpHeaders.ACCEPT);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return headers;
    }

    @VisibleForTesting
    protected void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @VisibleForTesting
    protected void setChannelTeamId(String teamId) {
        this.channelTeamId = teamId;
    }

    @VisibleForTesting
    protected void setMattermostUrl(String mattermostUrl) {
        this.mattermostUrl = mattermostUrl;
    }
}
