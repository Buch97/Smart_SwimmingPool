package it.unipi.aide.iot.bean.actuators;
import it.unipi.aide.iot.persistence.MySqlDbHandler;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.MediaTypeRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Light {
    private final List<CoapClient> clientLightStatusList = new ArrayList<>();
    private final List<CoapClient> clientLightColorList = new ArrayList<>();

    public void registerLight(String ip) {
        CoapClient newClientLightStatus = new CoapClient("coap://[" + ip + "]/light/status");
        CoapClient newClientLightColor = new CoapClient("coap://[" + ip + "]/light/color");

        clientLightStatusList.add(newClientLightStatus);
        clientLightColorList.add(newClientLightColor);
        MySqlDbHandler.getInstance().insertNewDevice(ip, "light");
        System.out.print("[REGISTRATION] The light: [" + ip + "] is now registered");
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

    public void lightSwitch(boolean on) {
        if(clientLightStatusList == null)
            return;

        String msg = "status=" + (on ? "ON" : "OFF");
        for(CoapClient clientLightStatus: clientLightStatusList) {
            //con la put invio all'endpoint di ogni luce il testo desiderato (faccio la put sulla risorsa definita su cooja)
            clientLightStatus.put(new CoapHandler() {
                @Override
                public void onLoad(CoapResponse coapResponse) {
                    if (coapResponse != null) {
                        if (!coapResponse.isSuccess())
                            System.out.print("[ERROR]Light Switch: PUT request unsuccessful");
                    }
                }

                @Override
                public void onError() {
                    System.err.print("[ERROR] Light Switch " + clientLightStatus.getURI() + "]");
                }
            }, msg, MediaTypeRegistry.TEXT_PLAIN);
        }
    }

    public void setLightColor(String color) {
        if(clientLightColorList == null)
            return;

        if(!Objects.equals(color, "red") & !Objects.equals(color, "green") & !Objects.equals(color, "yellow")) {
            System.out.println("Color not available, try with red, green or yellow");
            return;
        }
        String msg = "color=" + color;
        for(CoapClient clientLightColor: clientLightColorList) {
            clientLightColor.put(new CoapHandler() {
                @Override
                public void onLoad(CoapResponse coapResponse) {
                    if (!coapResponse.isSuccess())
                        System.out.print("[ERROR] Light Color: PUT request unsuccessful");
                }

                @Override
                public void onError() {
                    System.err.print("[ERROR] Light Color " + clientLightColor.getURI() + "]");
                }
            }, msg, MediaTypeRegistry.TEXT_PLAIN);
        }
    }


}
