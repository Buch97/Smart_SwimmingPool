#include "contiki.h"
#include "net/routing/routing.h"
#include "mqtt.h"
#include "net/ipv6/uip.h"
#include "net/ipv6/uip-icmp6.h"
#include "net/ipv6/sicslowpan.h"
#include "sys/etimer.h"
#include "os/sys/log.h"
#include "os/dev/button-hal.h"

#include <string.h>
#include <sys/node-id.h>

/*---------------------------------------------------------------------------*/
#define LOG_MODULE "mqtt-client-temperature"
#ifdef MQTT_CLIENT_CONF_LOG_LEVEL
#define LOG_LEVEL MQTT_CLIENT_CONF_LOG_LEVEL
#else
#define LOG_LEVEL LOG_LEVEL_DBG
#endif

/*---------------------------------------------------------------------------*/
/* MQTT broker address */
#define MQTT_CLIENT_BROKER_IP_ADDR "fd00::1"

static const char *broker_ip = MQTT_CLIENT_BROKER_IP_ADDR;

/* Default config values */
#define DEFAULT_BROKER_PORT         1883
#define DEFAULT_PUBLISH_INTERVAL    (30 * CLOCK_SECOND)

/*---------------------------------------------------------------------------*/
/* Various states */

static uint8_t state;

#define STATE_INIT          0
#define STATE_NET_OK        1
#define STATE_CONNECTING    2
#define STATE_CONNECTED     3
#define STATE_SUBSCRIBED    4
#define STATE_DISCONNECTED  5

/*---------------------------------------------------------------------------*/
PROCESS_NAME(mqtt_client_temp);
AUTOSTART_PROCESSES(&mqtt_client_temp);

/*---------------------------------------------------------------------------*/
#define MAX_TCP_SEGMENT_SIZE    32
#define CONFIG_IP_ADDR_STR_LEN   64

/*---------------------------------------------------------------------------*/
#define BUFFER_SIZE 64

static char client_id[BUFFER_SIZE];
static char pub_topic[BUFFER_SIZE];

static struct mqtt_connection conn;

#define STATE_MACHINE_PERIODIC     (CLOCK_SECOND)
static struct etimer periodic_timer;

mqtt_status_t status;
char broker_address[CONFIG_IP_ADDR_STR_LEN];

#define APP_BUFFER_SIZE 512
static char app_buffer[APP_BUFFER_SIZE];

static struct mqtt_message *msg_ptr = 0;

static bool inc_temp = false;
static bool dec_temp = false;
static int temperature = 25; 
static int variation = 0;

button_hal_button_t *btn;
/*---------------------------------------------------------------------------*/
PROCESS(mqtt_client_temp, "MQTT Client - Temperature");


/*---------------------------------------------------------------------------*/

static void pub_handler(const char *topic, uint16_t topic_len, const uint8_t *chunk, uint16_t chunk_len)
{
	LOG_INFO("Message received: topic='%s' (len=%u), chunk_len=%u\n", topic, topic_len, chunk_len);

	if(strcmp(topic, "temperature-command") != 0){
		LOG_ERR("Topic not valid!\n");
		return;
	}
	
	LOG_INFO("Received Actuator command\n");
	if (strcmp((const char*) chunk, "INC") == 0){
		LOG_INFO("Switch ON heating system to increment temperature \n");
		inc_temp = true;
		dec_temp = false;	
	}else if (strcmp((const char*) chunk, "DEC") == 0){
		LOG_INFO("Switch ON heating system to decrement temperature \n");	
		inc_temp = false;
		dec_temp = true;
	}else if (strcmp((const char*) chunk, "OFF") == 0){
		LOG_INFO("Turn OFF heating system \n");
		inc_temp = false;
		dec_temp = false;
	}
}

static void mqtt_event(struct mqtt_connection *m, mqtt_event_t event, void *data)
{
  switch (event) {
    case MQTT_EVENT_CONNECTED:
      printf("MQTT client connected\n");
      state = STATE_CONNECTED;
      break;

    case MQTT_EVENT_DISCONNECTED:
      printf("MQTT Disconnect. Reason %u\n", *((mqtt_event_t *)data));

      state = STATE_DISCONNECTED;
      /* Process poll to enforce reconnection */ 
      process_poll(&mqtt_client_temp);
      break;

    case MQTT_EVENT_PUBLISH: 
      msg_ptr = data;
      pub_handler(msg_ptr->topic, strlen(msg_ptr->topic), msg_ptr->payload_chunk, msg_ptr->payload_length);
      break;

    case MQTT_EVENT_SUBACK: 
      #if MQTT_311
      mqtt_suback_event_t *suback_event = (mqtt_suback_event_t *)data;
      if(suback_event->success) 
      	LOG_INFO("Application has subscribed to the topic\n");
      else 
      	LOG_ERR("Application failed to subscribe to topic (ret code %x)\n", suback_event->return_code);
      #else
      LOG_INFO("Application has subscribed to the topic\n");
      #endif
      break;
    case MQTT_EVENT_UNSUBACK: 
      LOG_INFO("Application is unsubscribed to topic successfully\n");
      break;

    case MQTT_EVENT_PUBACK: 
      printf("Publishing complete.\n");
      break;

    default:
      printf("Application got a unhandled MQTT event: %i\n", event);
      break;
  }
}
/*---------------------------------------------------------------------------*/

void adjustTemp() {
    if (inc_temp || dec_temp) {
        variation = (rand() % 2) + 1; // a value in [1,2]
        if (rand() % 10 < 8) {
            if (inc_temp)
                temperature += variation;
            else
                temperature -= variation;
        }
    } else { 
        if (rand() % 10 < 3) {
            variation = (rand() % 3) - 1; // a value in [-1, 1]
            temperature += variation;
        }
    }
}


/*---------------------------------------------------------------------------*/

PROCESS_THREAD(mqtt_client_temp, ev, data)
{
  PROCESS_BEGIN();

  printf("MQTT Client Process - Temperature\n");

  snprintf(client_id, BUFFER_SIZE, "%02x%02x%02x%02x%02x%02x",
                     linkaddr_node_addr.u8[0], linkaddr_node_addr.u8[1],
                     linkaddr_node_addr.u8[2], linkaddr_node_addr.u8[5],
                     linkaddr_node_addr.u8[6], linkaddr_node_addr.u8[7]);

  btn = button_hal_get_by_index(0);
  if(btn == NULL) {
	LOG_ERR("Unable to find button 0... exit");
	PROCESS_EXIT();
  }

  /* Broker registration */ 
  mqtt_register(&conn, &mqtt_client_temp, client_id, mqtt_event,
                  MAX_TCP_SEGMENT_SIZE);

  /* Initialize timer */ 
  etimer_set(&periodic_timer, STATE_MACHINE_PERIODIC);

  state = STATE_INIT;

  while (1) {
    PROCESS_YIELD();

    if ((ev == PROCESS_EVENT_TIMER && data == &periodic_timer) || 
	      ev == PROCESS_EVENT_POLL){

      if (state == STATE_INIT && uip_ds6_get_global(ADDR_PREFERRED) != NULL) {
        state = STATE_NET_OK;
      }

      if (state == STATE_NET_OK) {
        printf("Connecting to MQTT broker...\n");

        /* Copy MQTT broker address */
        memcpy(broker_address, broker_ip, strlen(broker_ip));
	
	/* Connect to MQTT broker */
        mqtt_connect(&conn, broker_address, DEFAULT_BROKER_PORT,
                     (DEFAULT_PUBLISH_INTERVAL * 3) / CLOCK_SECOND,
                     MQTT_CLEAN_SESSION_ON);
        state = STATE_CONNECTING;
      }

      if (state == STATE_CONNECTED){
	status = mqtt_subscribe(&conn, NULL, "temperature-command", MQTT_QOS_LEVEL_0);
	if(status == MQTT_STATUS_OUT_QUEUE_FULL) {
		LOG_ERR("Tried to subscribe but command queue was full!\n");
		PROCESS_EXIT();
	}
	state = STATE_SUBSCRIBED;
      }

      if (state == STATE_SUBSCRIBED) {
        /* Publish a message on topic "temperature*/
        sprintf(pub_topic, "%s", "temperature");
	adjustTemp();
        sprintf(app_buffer, "{\"nodeId\": %d, \"temperature\": %d}", node_id, temperature);

        mqtt_publish(&conn, NULL, pub_topic, (uint8_t *)app_buffer,
               strlen(app_buffer), MQTT_QOS_LEVEL_0, MQTT_RETAIN_OFF);
      }

     
      if (state == STATE_DISCONNECTED){
	LOG_ERR("Disconnected from MQTT broker\n");	
	state = STATE_INIT;
      }

      etimer_set(&periodic_timer, STATE_MACHINE_PERIODIC);
    } else if(ev == button_hal_press_event){
                if(inc_temp)
			inc_temp = false;
	    	else inc_temp = true;
            
    } else if (ev == PROCESS_EVENT_EXIT) {
      	    mqtt_disconnect(&conn);
    } else if (ev == PROCESS_EVENT_CONTINUE) {
            printf("MQTT client connection failed\n");
    }
  }

  PROCESS_END();
}
/*---------------------------------------------------------------------------*/
