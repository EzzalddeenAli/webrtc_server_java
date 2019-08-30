package com.example.demo;


import com.example.demo.bean.DeviceSession;
import com.example.demo.bean.EventData;
import com.example.demo.bean.RoomInfo;
import com.example.demo.bean.UserBean;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.example.demo.MemCons.rooms;

@ServerEndpoint("/ws/{userId}/{device}")
@Component
public class WebSocketServer {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServer.class);

    private Session session;
    private String userId;
    private int device;
    private static Gson gson = new Gson();
    private static String avatar = "http://img.xinxic.com/img/abec871cbeac880b.jpg";


    // 用户userId登录进来
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId, @PathParam("device") String de) {
        System.out.println("onOpen......");

        int device = Integer.parseInt(de);
        UserBean userBean = MemCons.userBeans.get(userId);
        if (userBean == null) {
            userBean = new UserBean(userId, avatar);
        }
        if (device == 0) {
            userBean.setPhoneSession(session, device);
            LOG.info("Phone用户登陆:" + userBean.getUserId() + ",session:" + session.getId());
        } else {
            userBean.setPcSession(session, device);
            LOG.info("PC用户登陆:" + userBean.getUserId() + ",session:" + session.getId());
        }
        this.device = device;
        this.session = session;
        this.userId = userId;

        //加入列表
        MemCons.userBeans.put(userId, userBean);

        // 登陆成功，返回个人信息
        EventData send = new EventData();
        send.setEventName("__login_success");
        Map<String, Object> map = new HashMap<>();
        map.put("userID", userId);
        map.put("avatar", avatar);
        send.setData(map);
        this.session.getAsyncRemote().sendText(gson.toJson(send));


    }

    // 用户下线
    @OnClose
    public void onClose() {
        System.out.println("onClose......");
        // 根据用户名查出房间,
        UserBean userBean = MemCons.userBeans.get(userId);
        if (userBean != null) {
            DeviceSession[] sessions = userBean.getSessions();
            if (device == 0) {
                try {
                    sessions[0].getSession().close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                sessions[0] = null;
                userBean.setSessions(sessions);
                MemCons.userBeans.put(userId, userBean);
                LOG.info("Phone用户离开:" + userBean.getUserId());
            } else {

                try {
                    sessions[1].getSession().close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                sessions[1] = null;
                userBean.setSessions(sessions);
                MemCons.userBeans.put(userId, userBean);
                LOG.info("PC用户离开:" + userBean.getUserId());
            }
            if (sessions[0] == null && sessions[1] == null) {
                MemCons.userBeans.remove(userId);
            }
        }


    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("receive data:" + message);
        handleMessage(message, session);
    }

    // 发送各种消息
    private void handleMessage(String message, Session session) {
        EventData data;
        try {
            data = gson.fromJson(message, EventData.class);
        } catch (JsonSyntaxException e) {
            System.out.println("json解析错误：" + message);
            return;
        }
        switch (data.getEventName()) {
            case "__create":
                createRoom(message, data.getData());
                break;
            case "__invite":
                invite(message, data.getData());
                break;
            case "__ring":
                ring(message, data.getData());
                break;
            case "__cancel":
                cancel(message, data.getData());
                break;
            case "__reject":
                reject(message, data.getData());
                break;
            case "__join":
                join(message, data.getData());
                break;
            case "__ice_candidate":
                iceCandidate(message, data.getData());
                break;
            case "__offer":
                offer(message, data.getData());
                break;
            case "__answer":
                answer(message, data.getData());
                break;
            default:
                break;
        }

    }


    // 创建房间
    private void createRoom(String message, Map<String, Object> data) {
        String room = (String) data.get("room");
        String userId = (String) data.get("userID");
        RoomInfo roomParam = rooms.get(room);
        // 没有这个房间
        if (roomParam == null) {
            int size = (int) data.getOrDefault("roomSize", 2);
            // 创建房间
            RoomInfo roomInfo = new RoomInfo();
            roomInfo.setMaxSize(size);

            CopyOnWriteArrayList<UserBean> copy = new CopyOnWriteArrayList<>();
            // 将自己加入到房间里
            UserBean my = MemCons.userBeans.get(userId);
            copy.add(my);
            roomInfo.setUserBeans(copy);

            // 将房间储存起来
            rooms.put(room, roomInfo);
        }

    }

    // 首次邀请
    private void invite(String message, Map<String, Object> data) {
        String userList = (String) data.get("userList");
        String[] users = userList.split(",");

        // 给其他人发送邀请
        for (String user : users) {
            UserBean userBean = MemCons.userBeans.get(user);
            if (userBean != null) {
                sendMsg(userBean, -1, message);
            }
        }


    }

    // 响铃回复
    private void ring(String message, Map<String, Object> data) {
        String inviteId = (String) data.get("toID");
        UserBean userBean = MemCons.userBeans.get(inviteId);
        if (userBean != null) {
            sendMsg(userBean, -1, message);
        }
    }

    // 取消拨出
    private void cancel(String message, Map<String, Object> data) {
        String userList = (String) data.get("userList");
        String[] users = userList.split(",");
        for (String userId : users) {
            UserBean userBean = MemCons.userBeans.get(userId);
            if (userBean != null) {
                sendMsg(userBean, -1, message);
            }
        }


    }

    // 拒绝接听
    private void reject(String message, Map<String, Object> data) {
        String inviteId = (String) data.get("inviteID");
        UserBean userBean = MemCons.userBeans.get(inviteId);
        if (userBean != null) {
            sendMsg(userBean, -1, message);
        }
    }

    // 加入房间
    private void join(String message, Map<String, Object> data) {

        String room = (String) data.get("room");
        String userID = (String) data.get("userID");

        RoomInfo roomInfo = rooms.get(room);
        CopyOnWriteArrayList<UserBean> roomUserBeans = roomInfo.getUserBeans();
        UserBean my = MemCons.userBeans.get(userID);


        // 1. 將我加入到房间
        roomUserBeans.add(my);
        roomInfo.setUserBeans(roomUserBeans);
        rooms.put(room, roomInfo);

        // 2. 返回房间里的所有人信息
        EventData send = new EventData();
        send.setEventName("__peers");
        Map<String, Object> map = new HashMap<>();
        String[] cons = new String[roomUserBeans.size()];
        for (int i = 0; i < roomUserBeans.size(); i++) {
            UserBean userBean = roomUserBeans.get(i);
            if (userBean.getUserId().equals(userID)) {
                continue;
            }
            cons[i] = userBean.getUserId();
        }
        map.put("connections", cons);
        map.put("you", userID);
        send.setData(map);
        sendMsg(my, -1, gson.toJson(send));


        // 3. 给房间里的其他人发送消息
        for (UserBean userBean : roomUserBeans) {
            if (userBean.getUserId().equals(userID)) {
                continue;
            }
            sendMsg(userBean, -1, message);
        }


    }

    // 发送offer
    private void offer(String message, Map<String, Object> data) {
        String userId = (String) data.get("userID");
        UserBean userBean = MemCons.userBeans.get(userId);
        sendMsg(userBean, -1, message);
    }

    // 发送answer
    private void answer(String message, Map<String, Object> data) {
        String userId = (String) data.get("userID");
        UserBean userBean = MemCons.userBeans.get(userId);
        sendMsg(userBean, -1, message);

    }

    // 发送ice信息
    private void iceCandidate(String message, Map<String, Object> data) {
        String userId = (String) data.get("userID");
        UserBean userBean = MemCons.userBeans.get(userId);
        sendMsg(userBean, -1, message);
    }

    // 给不同设备发送消息
    private void sendMsg(UserBean userBean, int device, String str) {
        if (device == 0) {
            Session phoneSession = userBean.getPhoneSession();
            if (phoneSession != null) {
                phoneSession.getAsyncRemote().sendText(str);
            }
        } else if (device == 1) {
            Session pcSession = userBean.getPcSession();
            if (pcSession != null) {
                pcSession.getAsyncRemote().sendText(str);
            }
        } else {
            Session phoneSession = userBean.getPhoneSession();
            if (phoneSession != null) {
                phoneSession.getAsyncRemote().sendText(str);
            }
            Session pcSession = userBean.getPcSession();
            if (pcSession != null) {
                pcSession.getAsyncRemote().sendText(str);
            }

        }

    }


}