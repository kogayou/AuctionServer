/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package auctionserver;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;
import javax.swing.*;
import javax.swing.text.*;

/**
 *
 * @author kogayou
 */
class ClientInfo {
    String ip;
    int port;
    String bidderName;
    int bidderID;
    boolean inAuction;
    String auctionName;
    double bidPrice;
}

class AuctionInfo {
    double startPrice;
    double curPrice;
    int bidderCnt;
    boolean isOver;
    java.util.Timer timer;
}

public class ServerKernel {
    
    public static DatagramSocket ds;
    
    private static JTextPane msgTextPane,auctionTextPane,clientTextPane;
    private static int bidderIDCount;
    private static TreeMap<String,ClientInfo> clients;
    private static TreeMap<String,AuctionInfo> auctions;
    private static String auctionStr;
    private static String clientStr;
    private static boolean serverInAuction;
    private static String serverAuctionName;
    private static long pendingTime;
    
    public static void appendTextPanes(JTextPane tp,String str,Color color) {
        Document docs=tp.getDocument();
        try {
            docs.insertString(docs.getLength(),ServerUtil.getTime(),ServerUtil.setAttributeSet(Color.LIGHT_GRAY));
            docs.insertString(docs.getLength(),str+"\n",ServerUtil.setAttributeSet(color));
        } catch (BadLocationException ex) {
            Logger.getLogger(ServerKernel.class.getName()).log(Level.SEVERE, null, ex);
        }
        tp.setCaretPosition(docs.getLength());
    }
    
    private static class auctionDone extends java.util.TimerTask {
        String auctionName;
        public auctionDone(String str) {
            auctionName=str;
        }
        public void run() {
            AuctionInfo auction=auctions.get(auctionName);
            auction.isOver=true;
            auction.timer.cancel();
            auctions.put(auctionName,auction);
            updateAuctions();
            for (String key:clients.keySet()) {
                ClientInfo client=clients.get(key);
                if (!client.inAuction||!client.auctionName.equals(auctionName)) {
                    continue;
                }
                if (client.bidPrice==auction.curPrice) {
                    appendTextPanes(msgTextPane,"<"+client.auctionName+"> "+client.bidderName+"("+client.bidderID+") 拍得该物品，成交价 "+auction.curPrice+" 元" ,Color.MAGENTA);
                    sendUpdate(client.ip,client.port,"05","<"+client.auctionName+"> "+"恭喜您成功拍得该物品，成交价 "+auction.curPrice+" 元");
                    sendAuctionAll("05","<"+client.auctionName+"> "+client.bidderName+"("+client.bidderID+") 拍得该物品，成交价 "+auction.curPrice+" 元",auctionName,client.bidderID);
                    break;
                }
            }
            sendUpdateAll("","");
        }
    }
    
    public static void init() {
        clients=new TreeMap<String,ClientInfo>();
        auctions=new TreeMap<String,AuctionInfo>();
        auctionStr="";
        clientStr="";
        serverInAuction=false;
        serverAuctionName="";
        bidderIDCount=10000;
        pendingTime=10;
    }
    
    public static void processCommand(String str) {
        if (!str.startsWith("/")) {
            appendTextPanes(msgTextPane,"指令必须以/开头",Color.RED);
            return;
        }
        if (str.startsWith("/msg ")) {
            processMsg(str.substring(4));
            return;
        }
        if (str.startsWith("/opennewauction ")) {
            processOpenNewAuction(str.substring(15));
            return;
        }
        if (str.startsWith("/list ")) {
            processList(str.substring(5));
            return;
        }
        if (str.startsWith("/enter ")) {
            processEnter(str.substring(6));
            return;
        }
        if (str.startsWith("/close ")) {
            processClose(str.substring(6));
            return;
        }
        if (str.trim().equals("/leave")) {
            processLeave();
            return;
        }
        if (str.startsWith("/kickout ")) {
            processKickout(str.substring(8));
            return;
        }
        if (str.startsWith("/settime ")) {
            processSettime(str.substring(8));
            return;
        }
        appendTextPanes(msgTextPane,"不支持的指令",Color.RED);     
    }
    
    public static void processDatagramPacket(DatagramPacket dp) {
        String ip=dp.getAddress().getHostAddress();
        int port=dp.getPort();
        String data=new String(dp.getData(),0,dp.getLength());
        if (!data.startsWith("/")) {
            sendMessage(ip,port,"01","指令必须以/开头");
        }
        if (data.startsWith("/login ")) {
            processLogin(ip,port,data.substring(6));
            return;
        }
        if (!clients.containsKey(ip+port)) {
            sendMessage(ip,port,"01","请先使用/login指令登录");
            return;
        }
        if (data.trim().equals("/quit")) {
            processQuit(ip,port);
            return;
        }
        if (data.startsWith("/join ")) {
            processJoin(ip,port,data.substring(5));
            return;
        }
        if (data.trim().equals("/leave")) {
            processLeave(ip,port);
            return;
        }
        if (data.startsWith("/list ")) {
            processList(ip,port,data.substring(5));
            return;
        }
        if (data.startsWith("/bid ")) {
            processBid(ip,port,data.substring(4));
            return;
        }
        sendMessage(ip,port,"01","不支持的指令");    
    }
    
    private static void processBid(String ip,int port,String str) {
        str=str.trim();
        double bidPrice=0;
        try {
            bidPrice=Double.parseDouble(str.substring(str.lastIndexOf(" ")+1));
        } catch (NumberFormatException ex) {
            sendUpdate(ip,port,"01","指令格式不正确");
            return;
        }
        ClientInfo client=clients.get(ip+port);
        if (!client.inAuction) {
            sendUpdate(ip,port,"01","您尚未加入任何竞拍室");
            return;
        }
        AuctionInfo auction=auctions.get(client.auctionName);
        if (auction.isOver) {
            sendUpdate(ip,port,"01","该竞拍室的竞拍已经结束");
            return;            
        }
        if (bidPrice<=auction.curPrice) {
            sendUpdate(ip,port,"01","出价必须大于当前竞拍价格");
            return;            
        }
        auction.curPrice=bidPrice;
        auction.timer.cancel();
        auction.timer=new java.util.Timer();
        auction.timer.schedule(new auctionDone(client.auctionName),pendingTime*1000);
        auctions.put(client.auctionName,auction);
        updateAuctions();
        client.bidPrice=bidPrice;
        clients.put(ip+port,client);
        updateClients();
        appendTextPanes(msgTextPane,"<"+client.auctionName+"> "+client.bidderName+"("+client.bidderID+") 出价 "+bidPrice+" 元",Color.MAGENTA);
        sendUpdate(ip,port,"05","<"+client.auctionName+"> "+"您出价 "+bidPrice+" 元");
        sendUpdate(ip,port,"05","<"+client.auctionName+"> "+pendingTime+"秒内无更高出价，您将拍得该物品");
        sendAuctionAll("05","<"+client.auctionName+"> "+client.bidderName+"("+client.bidderID+") 出价 "+bidPrice+" 元",client.auctionName,client.bidderID);
        sendAuctionAll("05","<"+client.auctionName+"> "+pendingTime+"秒内无更高出价，"+client.bidderName+"("+client.bidderID+") 将拍得该物品",client.auctionName,client.bidderID);
        sendUpdateAll("","");
    }
    
    private static void processClose(String str) {
        str=str.trim();
        if (!auctions.containsKey(str)) {
            appendTextPanes(msgTextPane,"竞拍室 \""+str+"\" 不存在",Color.RED);
            return;
        }
        if (serverAuctionName.equals(str)) {
            serverInAuction=false;
            serverAuctionName="";            
        }
        auctions.remove(str);
        updateAuctions();
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            if (client.inAuction&&client.auctionName.equals(str)) {
                sendUpdate(client.ip,client.port,"04","竞拍室 "+str+" 已关闭");
                client.inAuction=false;
                client.auctionName="";
                client.bidPrice=0;
                clients.put(key,client);
            }
        }
        updateClients();
        sendUpdateAll("","");
        appendTextPanes(msgTextPane,"竞拍室 "+str+" 已关闭",Color.GREEN);
    }
    
    private static void processEnter(String str) {
        str=str.trim();
        if (!auctions.containsKey(str)) {
            appendTextPanes(msgTextPane,"竞拍室 \""+str+"\" 不存在",Color.RED);
            return;
        }
        serverInAuction=true;
        serverAuctionName=str;
        appendTextPanes(msgTextPane,"进入竞拍室 "+str,Color.RED);
    }
    
    private static void processJoin(String ip,int port,String str) {
        str=str.trim();
        if (!auctions.containsKey(str)) {
            sendUpdate(ip,port,"01","竞拍室 \""+str+"\" 不存在");
            return;
        }
        AuctionInfo auction=auctions.get(str);
        ClientInfo client=clients.get(ip+port);
        if (client.inAuction) {
             sendUpdate(ip,port,"01","加入新竞拍室前请先使用/leave指令退出当前竞拍室");
             return;
        }
        auction.bidderCnt++;
        auctions.put(str,auction);
        updateAuctions();
        client.inAuction=true;
        client.auctionName=str;
        client.bidPrice=0;
        clients.put(ip+port,client);
        updateClients();
        appendTextPanes(msgTextPane,client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已进入竞拍室 "+str,Color.GRAY);
        sendUpdateAll("","");
        sendUpdate(ip,port,"03","您已进入竞拍室 "+str);
        sendAuctionAll("03",client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已进入竞拍室 "+str,str,client.bidderID);
    }
    
    private static void processKickout(String str) {
        str=str.trim();
        int bidderID=0;
        try {
            bidderID=Integer.parseInt(str);
        } catch (NumberFormatException ex) {
            appendTextPanes(msgTextPane,"指令格式不正确",Color.RED);
            return;
        }
        boolean findID=false;
        boolean needRestart=false;
        AuctionInfo auction;
        String auctionName="";
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            if (client.bidderID==bidderID) {
                findID=true;
                if (!client.inAuction) {
                    appendTextPanes(msgTextPane,client.bidderName+"("+client.bidderID+") 未加入竞拍室" ,Color.RED);
                    return;
                }
                auction=auctions.get(client.auctionName);
                appendTextPanes(msgTextPane,client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已被从竞拍室 "+client.auctionName+" 中移除",Color.GRAY);
                sendUpdate(client.ip,client.port,"03","您已被从竞拍室 "+client.auctionName+" 中移除");
                sendAuctionAll("03",client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已被从竞拍室 "+client.auctionName+" 中移除",client.auctionName,client.bidderID);
                if (auction.curPrice==client.bidPrice&&!auction.isOver) {
                    needRestart=true;
                    auctionName=client.auctionName;
                }
                auction.bidderCnt--;
                auctions.put(client.auctionName,auction);
                updateAuctions();
                client.inAuction=false;
                client.auctionName="";
                client.bidPrice=0;
                clients.put(key,client);
                updateClients();
                sendUpdateAll("","");
                break;
            }
        }
        if (!findID) {
            appendTextPanes(msgTextPane,"biddleID \""+bidderID+"\" 不存在",Color.RED);
            return;
        }
        if (needRestart) {
            restartAuction(auctionName);
        }
    }
    
    private static void processLeave() {
        if (!serverInAuction) {
            appendTextPanes(msgTextPane,"未进入竞拍室",Color.RED);
            return;
        }
        appendTextPanes(msgTextPane,"离开竞拍室 "+serverAuctionName,Color.RED);
        serverInAuction=false;
        serverAuctionName="";
    }
    
    private static void processLeave(String ip,int port) {
        ClientInfo client=clients.get(ip+port);
        if (!client.inAuction) {
             sendUpdate(ip,port,"01","您尚未加入任何竞拍室");
             return;
        }
        String str=client.auctionName;
        AuctionInfo auction=auctions.get(str);
        if (auction.curPrice==client.bidPrice&&!auction.isOver) {
             sendUpdate(ip,port,"01","您是当前最高出价者，不能离开该竞拍室");
             return;            
        }
        auction.bidderCnt--;
        auctions.put(str,auction);
        updateAuctions();
        client.inAuction=false;
        client.auctionName="";
        client.bidPrice=0;
        clients.put(ip+port,client);
        updateClients();
        appendTextPanes(msgTextPane,client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已离开竞拍室 "+str,Color.GRAY);
        sendUpdate(ip,port,"03","您已离开竞拍室 "+str);
        sendAuctionAll("03",client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已离开竞拍室 "+str,str,0);
        sendUpdateAll("","");
    }
    
    private static void processList(String str) {
        str=str.trim();
        if (str.isEmpty()&&!serverInAuction) {
            int bidderCnt=0;            
            for (String key:clients.keySet()) {
                ClientInfo client=clients.get(key);
                if (!client.inAuction) {
                    bidderCnt++;
                }
            }
            appendTextPanes(msgTextPane,"大厅 "+str+" 共"+bidderCnt+"人",Color.BLUE);
            for (String key:clients.keySet()) {
                ClientInfo client=clients.get(key);
                if (!client.inAuction) {
                    bidderCnt++;
                    appendTextPanes(msgTextPane,"竞拍者 "+client.bidderName+"("+client.bidderID+")",Color.BLUE);
                }
            }
            return;
        }
        if (str.isEmpty()&&serverInAuction) {
            str=serverAuctionName;
        }
        if (!auctions.containsKey(str)) {
            appendTextPanes(msgTextPane,"竞拍室 \""+str+"\" 不存在",Color.RED);
            return;
        }
        AuctionInfo auction=auctions.get(str);
        if (auction.isOver) {
            appendTextPanes(msgTextPane,"竞拍室 "+str+" 共"+auction.bidderCnt+"人，竞拍已经结束",Color.BLUE);
            return;
        }
        appendTextPanes(msgTextPane,"竞拍室 "+str+" 共"+auction.bidderCnt+"人，竞拍正在进行",Color.BLUE);
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            if (!client.inAuction||!client.auctionName.equals(str)) {
                continue;
            }
            if (client.bidPrice==0) {
                appendTextPanes(msgTextPane,"竞拍者 "+client.bidderName+"("+client.bidderID+") 未出价",Color.BLUE);
            } else {
                if (client.bidPrice==auction.curPrice) {
                    appendTextPanes(msgTextPane,"竞拍者 "+client.bidderName+"("+client.bidderID+") 出价: "+client.bidPrice+" 元 (最高价)",Color.BLUE);
                } else {
                    appendTextPanes(msgTextPane,"竞拍者 "+client.bidderName+"("+client.bidderID+") 出价: "+client.bidPrice+" 元",Color.BLUE);
                }
            }
        }       
    }
    
    private static void processList(String ip,int port,String str) {
        str=str.trim();
        if (str.isEmpty()) {
            ClientInfo client=clients.get(ip+port);
            if (client.inAuction) {
                str=client.auctionName;
            } else {
                int bidderCnt=0;            
                for (String key:clients.keySet()) {
                    client=clients.get(key);
                    if (!client.inAuction) {
                        bidderCnt++;
                    }
                }
                sendUpdate(ip,port,"02","大厅 "+str+" 共"+bidderCnt+"人");
                for (String key:clients.keySet()) {
                    client=clients.get(key);
                    if (!client.inAuction) {
                        bidderCnt++;
                        sendUpdate(ip,port,"02","竞拍者 "+client.bidderName+"("+client.bidderID+")");
                    }
                }
                return;  
            }      
        }
        if (!auctions.containsKey(str)) {
            sendUpdate(ip,port,"01","竞拍室 \""+str+"\" 不存在");
            return;
        }
        AuctionInfo auction=auctions.get(str);
        if (auction.isOver) {
            sendUpdate(ip,port,"02","竞拍室 "+str+" 共"+auction.bidderCnt+"人，竞拍已经结束");
            return;
        }
        sendUpdate(ip,port,"02","竞拍室 "+str+" 共"+auction.bidderCnt+"人，竞拍正在进行");
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            if (!client.inAuction||!client.auctionName.equals(str)) {
                continue;
            }
            if (client.bidPrice==0) {
                sendUpdate(ip,port,"02","竞拍者 "+client.bidderName+"("+client.bidderID+") 未出价");
            } else {
                if (client.bidPrice==auction.curPrice) {
                    sendUpdate(ip,port,"02","竞拍者 "+client.bidderName+"("+client.bidderID+") 出价: "+client.bidPrice+" 元 (最高价)");
                } else {
                    sendUpdate(ip,port,"02","竞拍者 "+client.bidderName+"("+client.bidderID+") 出价: "+client.bidPrice+" 元");
                }
            }
       }       
    }
    
    private static void processLogin(String ip,int port,String str) {
        if (clients.containsKey(ip+port)) {
            sendUpdate(ip,port,"01","请不要重复登录");
            return;
        }
        str=str.trim();
        if (str.isEmpty()) {
            sendMessage(ip,port,"01","用户名不能为空");
            return;
        }
        ClientInfo client=new ClientInfo();
        client.ip=ip;
        client.port=port;
        client.bidderName=str;
        client.bidderID=bidderIDCount++;
        client.inAuction=false;
        client.auctionName="";
        client.bidPrice=0;
        clients.put(ip+port,client);
        updateClients();
        appendTextPanes(msgTextPane,client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已登录",Color.RED);
        sendUpdate(ip,port,"01","您以 "+client.bidderName+"("+client.bidderID+") 身份成功登录");
        sendUpdateAll("","");
    }
    
    private static void processMsg(String str) {
        str=str.trim();
        boolean isBroadcast=true;
        boolean isIDexisting=true;
        int bidderID=0;
        int msgCnt=0;
        if (str.startsWith("[")&&str.indexOf("]")>0) {
            try {
                bidderID=Integer.parseInt(str.substring(1,str.indexOf("]")));
            } catch (NumberFormatException ex) {
                appendTextPanes(msgTextPane,"\""+str.substring(1,str.indexOf("]"))+"\" 非合法bidderID",Color.RED);
                return;
            }               
            isBroadcast=false;
            isIDexisting=false;
            str=str.substring(str.indexOf("]")+1).trim();
        }
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            if (client.bidderID==bidderID) {
                isIDexisting=true;
            }
            if (isBroadcast||client.bidderID==bidderID) {
                if (isBroadcast) {
                    sendUpdate(client.ip,client.port,"02","<服务器广播> "+str);
                    msgCnt++;
                } else {
                    sendUpdate(client.ip,client.port,"02","<服务器对您说> "+str);
                    msgCnt++;
                }
            }               
        }
        if (!isIDexisting) {
            appendTextPanes(msgTextPane,"bidderID \""+bidderID+"\" 不存在",Color.RED);
            return;
        }
        appendTextPanes(msgTextPane,"已向"+msgCnt+"位竞拍者发送信息",Color.RED);
    }
    
    private static void processOpenNewAuction(String str) {
        str=str.trim();
        if (str.lastIndexOf(" ")<0) {
            appendTextPanes(msgTextPane,"指令格式不正确",Color.RED);
            return;
        }
        String auctionName=str.substring(0,str.lastIndexOf(" ")).trim();
        double startPrice=0;
        try {
            startPrice=Double.parseDouble(str.substring(str.lastIndexOf(" ")+1));
        } catch (NumberFormatException ex) {
            appendTextPanes(msgTextPane,"指令格式不正确",Color.RED);
            return;
        }
        if (auctions.containsKey(auctionName)) {
            appendTextPanes(msgTextPane,"该竞拍室已存在",Color.RED);
            return;
        }
        if (startPrice<=0) {
            appendTextPanes(msgTextPane,"起拍价必须为正",Color.RED);
            return;
        }
        if (auctionName.isEmpty()) {
            appendTextPanes(msgTextPane,"竞拍室名称不能为空",Color.RED);
            return;
        }
        AuctionInfo auction=new AuctionInfo();
        auction.startPrice=startPrice;
        auction.curPrice=startPrice;
        auction.bidderCnt=0;
        auction.isOver=false;
        auction.timer=new java.util.Timer();
        auctions.put(auctionName,auction);
        updateAuctions();
        sendUpdateAll("04","竞拍室 "+auctionName+" 已开启，起拍价为 "+startPrice+" 元");
        appendTextPanes(msgTextPane,"竞拍室 "+auctionName+" 已开启，起拍价为 "+startPrice+" 元",Color.GREEN);
    }
    
    private static void processQuit(String ip,int port) {
        boolean needRestart=false;
        String auctionName="";
        if (!clients.containsKey(ip+port)) {
            return;
        }
        ClientInfo client=clients.get(ip+port);
        appendTextPanes(msgTextPane,client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已退出",Color.RED);
        sendMessage(ip,port,"01","您已退出登录");
        sendMessage(ip,port,"10","");
        sendMessage(ip,port,"20","");
        if (client.inAuction) {
            AuctionInfo auction=auctions.get(client.auctionName);
            sendAuctionAll("03",client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"] 已离开竞拍室 "+client.auctionName,client.auctionName,client.bidderID);
            auction.bidderCnt--;
            if (auction.curPrice==client.bidPrice&&!auction.isOver) {
                needRestart=true;
                auctionName=client.auctionName;
            }
            auctions.put(client.auctionName,auction);
            updateAuctions();
        }
        clients.remove(ip+port);
        updateClients();
        sendUpdateAll("","");
        if (needRestart) {
            restartAuction(auctionName);
        }
    }
    
    private static void processSettime(String str) {
        str=str.trim();
        long newPendingTime=10;
        try {
            newPendingTime=Long.parseLong(str);
        } catch (NumberFormatException ex) {
            appendTextPanes(msgTextPane,"指令格式不正确",Color.RED);
            return;
        }
        if (newPendingTime<=0) {
            appendTextPanes(msgTextPane,"等待秒数必须为正整数",Color.RED);
            return;
        }
        pendingTime=newPendingTime;
        appendTextPanes(msgTextPane,"等待秒数设定为"+newPendingTime+"秒",Color.RED);
    }
    
    private static void restartAuction(String auctionName) {
        AuctionInfo auction=auctions.get(auctionName);
        auction.isOver=false;
        auction.curPrice=auction.startPrice;
        auction.timer.cancel();
        auctions.put(auctionName,auction);
        updateAuctions();
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            if (!client.inAuction||!client.auctionName.equals(auctionName)) {
                continue;
            }
            client.bidPrice=0;
            clients.put(key,client);
        }
        updateClients();
        appendTextPanes(msgTextPane,"竞拍室 "+auctionName+" 重新开启，起拍价为 "+auction.startPrice+" 元",Color.GREEN);
        sendUpdateAll("04","竞拍室 "+auctionName+" 重新开启，起拍价为 "+auction.startPrice+" 元");
    }
    
    private static void sendAuctionAll(String pattern,String str,String auctionName,int exceptionID) {
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            if (!client.inAuction||!client.auctionName.equals(auctionName)||client.bidderID==exceptionID) {
                continue;
            }
            sendUpdate(client.ip,client.port,pattern,str);
        }
    }
    
    private static void sendMessage(String ip,int port,String pattern,String str) {
        if (pattern.length()==0) {
            return;
        }
        byte[] buf=(pattern+str).getBytes();             
        try {
            DatagramPacket dp=new DatagramPacket(buf,buf.length,InetAddress.getByName(ip),port);
            ds.send(dp);
        } catch (NumberFormatException | IOException ex) {
            Logger.getLogger(ServerKernel.class.getName()).log(Level.SEVERE, null, ex);
        }             
    }
    
    private static void sendUpdate(String ip,int port,String pattern,String str) {
        sendMessage(ip,port,pattern,str);
        sendMessage(ip,port,"10",auctionStr);
        sendMessage(ip,port,"20",clientStr);
    }
    
    private static void sendUpdateAll(String pattern,String str) {
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            sendUpdate(client.ip,client.port,pattern,str);
        }
    }
    
    public static void serverQuit() {
        for (String key:auctions.keySet()) {
            AuctionInfo auction=auctions.get(key);
            auction.timer.cancel();
        }
        auctionStr="";
        clientStr="";
        sendUpdateAll("01","服务器已关闭");
        appendTextPanes(msgTextPane,"服务器已关闭",Color.RED);
        updateTextPanes(auctionTextPane,auctionStr,new Color(206,123,0));
        updateTextPanes(clientTextPane,clientStr,new Color(206,123,0));
        ds.close();
    }
    
    public static void setTextPanes(JTextPane msgTextPane,JTextPane auctionTextPane,JTextPane clientTextPane) {
        ServerKernel.msgTextPane=msgTextPane;
        ServerKernel.auctionTextPane=auctionTextPane;
        ServerKernel.clientTextPane=clientTextPane;
    }
    
    private static void updateAuctions() {
        auctionStr="";
        for (String key:auctions.keySet()) {
            AuctionInfo auction=auctions.get(key);
            if (auction.isOver) {
                auctionStr+=key+" (竞拍结束) ------ 成交价: "+auction.curPrice+" 元\n";
            } else {
                auctionStr+=key+" ("+auction.bidderCnt+"人竞拍中) ------ 现价: "+auction.curPrice+" 元\n";
            }
        }
        updateTextPanes(auctionTextPane,auctionStr,new Color(206,123,0));        
    }
    
    private static void updateClients() {
        clientStr="";
        for (String key:clients.keySet()) {
            ClientInfo client=clients.get(key);
            if (client.inAuction) {
                clientStr+="★ ";
            } else {
                clientStr+="☆ ";
            }
            clientStr+=client.bidderName+"("+client.bidderID+") ["+client.ip+":"+client.port+"]\n";
        }
        updateTextPanes(clientTextPane,clientStr,new Color(206,123,0));
    }

    public static void updateTextPanes(JTextPane tp,String str,Color color) {
        Document docs=tp.getDocument();
        try {
            docs.remove(0,docs.getLength());
            docs.insertString(0,str,ServerUtil.setAttributeSet(color));
        } catch (BadLocationException ex) {
            Logger.getLogger(ServerKernel.class.getName()).log(Level.SEVERE, null, ex);
        }       
    }
}
