package it.unipi.aide.iot.bean.coap_actuators;

import it.unipi.aide.iot.persistence.MySqlDbHandler;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.MediaTypeRegistry;

import java.util.ArrayList;
import java.util.List;

public class WaterPump {
    public static boolean lastStatus;
    private static final List<CoapClient> waterPumpEndpoints = new ArrayList<>();

    public void registerWaterPump(String ip) {
        CoapClient waterPumpEndpoint = new CoapClient("coap://[" + ip + "]/water_pump");
        waterPumpEndpoints.add(waterPumpEndpoint);
        MySqlDbHandler.getInstance().insertNewDevice(ip, "water_pump");
        System.out.print("[REGISTRATION] The water pump: [" + ip + "] is now registered");
    }

    public void unregisterWaterPump(String ip) {
        for(int i=0; i<waterPumpEndpoints.size(); i++) {
            if(waterPumpEndpoints.get(i).getURI().equals(ip)) {
                waterPumpEndpoints.remove(i);
            }
        }
        MySqlDbHandler.getInstance().removeDevice(ip, "water_pump");
        System.out.print("Device " + ip + " removed detached from endpoint and removed from db");
    }

    public static void switchWaterPump(){
        if(waterPumpEndpoints.size() == 0)
            return;

        String msg;
        if(lastStatus) {
            msg = "OFF";
            lastStatus = false;
        }
        else {
            msg = "ON";
            lastStatus = true;
        }

        for(CoapClient waterPumpEndpoint: waterPumpEndpoints) {
            waterPumpEndpoint.put(new CoapHandler() {
                @Override
                public void onLoad(CoapResponse coapResponse) {
                    if (coapResponse != null) {
                        if (!coapResponse.isSuccess())
                            System.out.print("[ERROR]Water Pump Switching: PUT request unsuccessful");
                    }
                }

                @Override
                public void onError() {
                    System.err.print("[ERROR] Water Pump Switching " + waterPumpEndpoint.getURI() + "]");
                }
            }, msg, MediaTypeRegistry.TEXT_PLAIN);
        }
    }
}
