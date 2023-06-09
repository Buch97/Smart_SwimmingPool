package it.unipi.aide.iot.bean.coap_actuators;
import it.unipi.aide.iot.persistence.MySqlDbHandler;
import it.unipi.aide.iot.utility.Logger;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Light {
    private static final List<CoapClient> clientLightStatusList = new ArrayList<>();
    private static final List<CoapClient> clientLightColorList = new ArrayList<>();
    public static boolean lastStatus;
    public static String currentColor;
    private Logger logger = Logger.getInstance();

    public void registerLight(String ip) {
        CoapClient newClientLightStatus = new CoapClient("coap://[" + ip + "]/light/switch");
        CoapClient newClientLightColor = new CoapClient("coap://[" + ip + "]/light/color");

        clientLightStatusList.add(newClientLightStatus);
        clientLightColorList.add(newClientLightColor);
        MySqlDbHandler.getInstance().insertNewDevice(ip, "light");
        logger.logInfo("[REGISTRATION] The light: [" + ip + "] is now registered");
    }

    public void unregisterLight(String ip) {
        for(int i=0; i<clientLightStatusList.size(); i++) {
            if(clientLightStatusList.get(i).getURI().equals(ip)) {
                clientLightStatusList.remove(i);
                clientLightColorList.remove(i);
            }
        }
        MySqlDbHandler.getInstance().removeDevice(ip, "light");
        System.out.print("Device removed detached from endpoint and removed from db");
    }

    public static void lightSwitch(boolean status) {
        if(clientLightStatusList.size() == 0)
            return;

        String msg = "mode=" + (status ? "ON" : "OFF");
        lastStatus = status;
        for(CoapClient clientLightStatusEndpoint : clientLightStatusList) {
            clientLightStatusEndpoint.put(new CoapHandler() {
                @Override
                public void onLoad(CoapResponse coapResponse) {
                    if (coapResponse != null) {
                        if (!coapResponse.isSuccess())
                            System.out.print("[ERROR] Light switch: PUT request unsuccessful");
                    }
                }

                @Override
                public void onError() {
                    System.err.print("[ERROR] Light switch " + clientLightStatusEndpoint.getURI() + "]");
                }
            }, msg, MediaTypeRegistry.TEXT_PLAIN);
        }
    }

    public static void setLightColor(String color) {
        if(clientLightColorList.size() == 0)
            return;

        if(!Objects.equals(color, "r") & !Objects.equals(color, "g") & !Objects.equals(color, "y")) {
            System.out.println("Color not available, try with red(r), green(g) or yellow(y)");
            return;
        }

        switch (color) {
            case "b":
                currentColor = "y";
                break;
            case "r":
                currentColor = "r";
                break;
            case "g":
                currentColor = "g";
                break;
        }


        String msg = "color=" + color;
        for(CoapClient clientLightColorEndpoint : clientLightColorList) {
            clientLightColorEndpoint.put(new CoapHandler() {
                @Override
                public void onLoad(CoapResponse coapResponse) {
                    if (coapResponse != null) {
                        if (!coapResponse.isSuccess())
                            System.out.print("[ERROR] Light color switch: PUT request unsuccessful");
                    }
                }

                @Override
                public void onError() {
                    System.err.print("[ERROR] Light color switch " + clientLightColorEndpoint.getURI() + "]");
                }
            }, msg, MediaTypeRegistry.TEXT_PLAIN);
        }
    }

    public static String currentColor(){
        return currentColor;
    }

    public static boolean isLastStatus() {
        return lastStatus;
    }
}
