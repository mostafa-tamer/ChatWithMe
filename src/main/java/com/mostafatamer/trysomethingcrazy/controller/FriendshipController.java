package com.mostafatamer.trysomethingcrazy.controller;

import com.mostafatamer.trysomethingcrazy.constants.MessageBrokers;
import com.mostafatamer.trysomethingcrazy.domain.ApiResponse;
import com.mostafatamer.trysomethingcrazy.domain.dto.UserDto;
import com.mostafatamer.trysomethingcrazy.domain.dto.chat.ChatDto;
import com.mostafatamer.trysomethingcrazy.domain.dto.firendRequest.FriendRequestDto;
import com.mostafatamer.trysomethingcrazy.domain.dto.firendRequest.SendFriendRequestDto;
import com.mostafatamer.trysomethingcrazy.domain.entity.ChatEntity;
import com.mostafatamer.trysomethingcrazy.domain.entity.FriendRequestCompositeKey;
import com.mostafatamer.trysomethingcrazy.domain.entity.FriendRequestEntity;
import com.mostafatamer.trysomethingcrazy.domain.entity.UserEntity;
import com.mostafatamer.trysomethingcrazy.domain.enumeration.MessageType;
import com.mostafatamer.trysomethingcrazy.domain.firebase.AcceptFriendRequest;
import com.mostafatamer.trysomethingcrazy.domain.firebase.CloudMessage;
import com.mostafatamer.trysomethingcrazy.exceptions.ClientException;
import com.mostafatamer.trysomethingcrazy.mappers.impl.UserMapper;
import com.mostafatamer.trysomethingcrazy.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Log
@RestController
@RequiredArgsConstructor
@RequestMapping("/friendship")
public class FriendshipController {

    private final SimpMessagingTemplate messagingTemplate;
    private final FriendshipService friendshipService;
    private final ChatService chatService;
    private final UserService userService;
    private final UserMapper userMapper;
    private final FirebaseMessagingService firebaseMessagingService;


    @PutMapping("/sendFriendRequest")
    public ApiResponse<SendFriendRequestDto> sendFriendRequest(@RequestBody @Valid SendFriendRequestDto sendFriendRequestDto) {
        UserEntity sender = userService.findByUsername(AuthenticationService.getUserEntity().getUsername());
        UserEntity receiver = userService.findByUsername(sendFriendRequestDto.getReceiverUsername());

        sendFriendRequestValidation(sender, receiver);

        saveFriendRequest(sendFriendRequestDto, receiver, sender);

        notifySendFriendRequestTopic(receiver,
                userMapper.entityToDto(sender),
                sendFriendRequestDto.getMessage());

        notifyFirebaseThatFriendRequestAccepted(receiver,
                userMapper.entityToDto(sender),
                sendFriendRequestDto.getMessage());

        return ApiResponse.<SendFriendRequestDto>builder()
                .data(sendFriendRequestDto)
                .build();
    }

    @GetMapping("/friendRequests")
    ApiResponse<List<FriendRequestDto>> friendRequests() throws InterruptedException {
        Thread.sleep(750);
        UserEntity me = AuthenticationService.getUserEntity();

        List<FriendRequestEntity> friendRequestsEntities = friendshipService.findAllByReceiver(me);

        List<FriendRequestDto> friendRequests = friendRequestsEntities.stream()
                .map(requestEntity -> {
                    UserEntity sender = userService.findById(
                            requestEntity.getFriendRequestCompositeKey()
                                    .getSenderId()
                    );

                    return FriendRequestDto.builder()
                            .sender(userMapper.entityToDto(sender))
                            .message(requestEntity.getMessage())
                            .build();
                }).toList();

        return ApiResponse.<List<FriendRequestDto>>builder()
                .data(friendRequests)
                .build();
    }

    @PostMapping("/acceptFriendRequest")
    ApiResponse<UserDto> acceptFriendRequest(@DestinationVariable String senderUsername) {
        UserEntity sender = userService.findByUsername(senderUsername);
        UserEntity receiver = userService.findByUsername(AuthenticationService.getUserEntity().getUsername());

        List<UserEntity> senderFriends = sender.getFriends();
        List<UserEntity> receiverFriends = receiver.getFriends();

        acceptFriendRequestValidation(receiverFriends, sender, senderFriends, receiver);

        makeSenderAndReceiverFriends(receiverFriends, sender, senderFriends, receiver);

        deleteFriendRequest(sender, receiver);

        ChatEntity savedChat = saveChatBetweenSenderAndReceiver(sender, receiver);

        notifyAcceptFriendRequestTopic(sender, savedChat);

        notifyFirebaseThatFriendRequestAccepted(sender, receiver);

        return ApiResponse.<UserDto>builder()
                .data(userMapper.entityToDto(sender))
                .build();
    }

    @PostMapping("/removeFriendRequest")
    ApiResponse<UserDto> removeFriendRequest(@DestinationVariable String senderUsername) {
        UserEntity sender = userService.findByUsername(senderUsername);
        UserEntity receiver = userService.findByUsername(AuthenticationService.getUserEntity().getUsername());

        deleteFriendRequest(sender, receiver);

        return ApiResponse.<UserDto>builder()
                .data(userMapper.entityToDto(sender))
                .build();
    }

    @GetMapping("/friends")
    ApiResponse<List<UserDto>> friends() {
        UserEntity caller = AuthenticationService.getUserEntity();
        List<UserEntity> friends = userService.findByUsername(caller.getUsername()).getFriends();
        return ApiResponse.<List<UserDto>>builder()
                .data(friends.stream().map(userMapper::entityToDto).toList())
                .build();
    }

    @GetMapping("/totalNumberOfFriendRequests")
    ApiResponse<Integer> totalNumberOfFriends() {
        UserEntity caller = AuthenticationService.getUserEntity();
        Integer friendsNumber = friendshipService.findAllByReceiver(caller).size();

        return ApiResponse.<Integer>builder()
                .data(friendsNumber)
                .build();
    }

    @PostMapping("/remove_friend")
    ApiResponse<UserDto> removeFriend(@DestinationVariable String friendUsername) {
        UserEntity client = userService.findByUsername(AuthenticationService.getUserEntity().getUsername());
        UserEntity friend = userService.findByUsername(friendUsername);

        System.out.println(friend.getFriends());
        System.out.println(client.getFriends());

        if (!friend.getFriends().contains(client)) {
            throw new ClientException("You are not already friends!");
        }

        client.getFriends().remove(friend);
        friend.getFriends().remove(client);

        userService.save(client);
        userService.save(friend);

        ChatEntity chat = chatService.findChatOfTwoFriends(client, friend);


        chat.getMembers().clear();

        chatService.updateChat(chat);

        chatService.removeChat(chat);

        String destination = MessageBrokers.REMOVE_FRIEND + "/" + friendUsername;

        messagingTemplate.convertAndSend(destination, ChatDto.builder()
                .tag(chat.getTag())
                .build());

        return ApiResponse.<UserDto>builder()
                .data(userMapper.entityToDto(friend))
                .build();
    }

    private void notifyFirebaseThatFriendRequestAccepted(UserEntity receiver, UserDto sender, String message) {
        firebaseMessagingService.sendClientMessage(
                receiver.getFirebaseToken(),
                CloudMessage.builder()
                        .messageType(MessageType.FRIEND_REQUEST)
                        .data(FriendRequestDto.builder()
                                .sender(sender)
                                .message(message)
                                .build())
                        .build());
    }

    private void notifySendFriendRequestTopic(UserEntity receiver, UserDto sender, String message) {
        String destination = MessageBrokers.SEND_FRIEND_REQUEST + "/" + receiver.getUsername();
        messagingTemplate.convertAndSend(
                destination, FriendRequestDto.builder()
                        .sender(sender)
                        .message(message)
                        .build()
        );
    }

    private void saveFriendRequest(SendFriendRequestDto sendFriendRequestDto, UserEntity receiver, UserEntity sender) {
        FriendRequestEntity friendRequestEntity = FriendRequestEntity.builder()
                .friendRequestCompositeKey(
                        FriendRequestCompositeKey.builder()
                                .receiver(receiver)
                                .senderId(sender.getId())
                                .build()
                ).message(sendFriendRequestDto.getMessage())
                .build();

        friendshipService.save(friendRequestEntity);
    }

    private void sendFriendRequestValidation(UserEntity routCaller, UserEntity receiver) {
        if (routCaller.getUsername().equals(receiver.getUsername()))
            throw new ClientException("sender and receiver are the same");

        if (receiver.getFriends().contains(routCaller) || routCaller.getFriends().contains(receiver))
            throw new ClientException("sender and receiver are already friends");

        if (friendshipService.isFriendRequestAlreadySent(routCaller.getId(), receiver.getId()))
            throw new ClientException("request already sent!");
    }

    private void notifyFirebaseThatFriendRequestAccepted(UserEntity sender, UserEntity receiver) {
        firebaseMessagingService.sendClientMessage(
                sender.getFirebaseToken(), CloudMessage.builder()
                        .messageType(MessageType.FRIEND_REQUEST_ACCEPTED)
                        .data(AcceptFriendRequest.builder()
                                .receiver(userMapper.entityToDto(receiver))
                                .build()
                        ).build()
        );
    }

    private void notifyAcceptFriendRequestTopic(UserEntity sender, ChatEntity savedChat) {
        String destination = MessageBrokers.ACCEPT_FRIEND_REQUEST + "/" + sender.getUsername();

        ChatDto chat = ChatDto.builder()
                .members(savedChat.getMembers().stream()
                        .map(userMapper::entityToDto)
                        .toList())
                .tag(savedChat.getTag())
                .lastMessage(null)
                .build();

        messagingTemplate.convertAndSend(destination, chat);
    }

    private ChatEntity saveChatBetweenSenderAndReceiver(UserEntity sender, UserEntity receiver) {
        return chatService.createChat(
                ChatEntity.builder()
                        .members(List.of(sender, receiver))
                        .build()
        );
    }

    private void deleteFriendRequest(UserEntity sender, UserEntity receiver) {
        friendshipService.deleteFriendRequestsById(FriendRequestCompositeKey.builder()
                .senderId(sender.getId())
                .receiver(receiver)
                .build());
    }

    private void makeSenderAndReceiverFriends(List<UserEntity> receiverFriends, UserEntity sender, List<UserEntity> senderFriends, UserEntity receiver) {
        receiverFriends.add(sender);
        senderFriends.add(receiver);

        userService.save(sender);
        userService.save(receiver);
    }

    private void acceptFriendRequestValidation(List<UserEntity> routCallerFriends, UserEntity sender, List<UserEntity> senderFriends, UserEntity receiver) {
        if (routCallerFriends.contains(sender) || senderFriends.contains(receiver))
            throw new ClientException("sender and receiver are already friends");

        if (!friendshipService.isFriendRequestAlreadySent(sender.getId(), receiver.getId()))
            throw new ClientException("there is not friend request sent");
    }
}

