package com.projectx.kafka.connector;


import com.projectx.kafka.connector.ssl.SSLUtils;
import com.projectx.kafka.connector.ssl.Version;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import javax.net.ssl.SSLSocketFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.apache.logging.log4j.message.MapMessage.MapFormat.JSON;


public class MqttSourceConnectorTask extends SourceTask implements MqttCallback {

    private MqttClient mqttClient;
    private String kafkaTopic;
    private String mqttTopic;
    private String mqttClientId;
    private String connectorName;
    private MqttSourceConnectorConfig connectorConfiguration;
    private SSLSocketFactory sslSocketFactory;
    BlockingQueue<SourceRecord> mqttRecordQueue = new LinkedBlockingQueue<SourceRecord>();
    private static final Logger logger = LogManager.getLogger(MqttSourceConnectorTask.class);
    JSONParser jsonParser=new JSONParser();

    private void initMqttClient() {

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        mqttConnectOptions.setServerURIs(new String[] {connectorConfiguration.getString("mqtt.connector.broker.uri")});
        mqttConnectOptions.setConnectionTimeout(connectorConfiguration.getInt("mqtt.connector.connection_timeout"));
        mqttConnectOptions.setKeepAliveInterval(connectorConfiguration.getInt("mqtt.connector.keep_alive"));
        mqttConnectOptions.setCleanSession(connectorConfiguration.getBoolean("mqtt.connector.clean_session"));
        mqttConnectOptions.setKeepAliveInterval(connectorConfiguration.getInt("mqtt.connector.connection_timeout"));
        if (connectorConfiguration.getBoolean("mqtt.connector.ssl")) {
            logger.info("SSL TRUE for MqttSourceConnectorTask: '{}, and mqtt client: '{}'.", connectorName, mqttClientId);
            try {
                String caCrtFilePath = connectorConfiguration.getString("mqtt.connector.ssl.ca");
                String crtFilePath = connectorConfiguration.getString("mqtt.connector.ssl.crt");
                String keyFilePath = connectorConfiguration.getString("mqtt.connector.ssl.key");
                SSLUtils sslUtils = new SSLUtils(caCrtFilePath, crtFilePath, keyFilePath);
                sslSocketFactory = sslUtils.getMqttSocketFactory();
                mqttConnectOptions.setSocketFactory(sslSocketFactory);
            } catch (Exception e) {
                logger.error("Not able to create SSLSocketfactory: '{}', for mqtt client: '{}', and connector: '{}'", sslSocketFactory, mqttClientId, connectorName);
                logger.error(e);
            }
        } else {
            logger.info("SSL FALSE for MqttSourceConnectorTask: '{}, and mqtt client: '{}'.", connectorName, mqttClientId);
        }

        try {
            mqttClient = new MqttClient(connectorConfiguration.getString("mqtt.connector.broker.uri"), mqttClientId, new MemoryPersistence());
            mqttClient.setCallback(this);
            mqttClient.connect(mqttConnectOptions);
            logger.info("SUCCESSFULL MQTT CONNECTION for AsamMqttSourceConnectorTask: '{}, and mqtt client: '{}'.", connectorName, mqttClientId);
        } catch (MqttException e) {
            logger.error("FAILED MQTT CONNECTION for AsamMqttSourceConnectorTask: '{}, and mqtt client: '{}'.", connectorName, mqttClientId);
            logger.error(e);
        }

        try {
            mqttClient.subscribe(mqttTopic, connectorConfiguration.getInt("mqtt.connector.qos"));
            logger.info("SUCCESSFULL MQTT CONNECTION for MqttSinkConnectorTask: '{}, and mqtt client: '{}'.", connectorName, mqttClientId);
        } catch (MqttException e) {
            logger.error("FAILED MQTT CONNECTION for MqttSinkConnectorTask: '{}, and mqtt client: '{}'.", connectorName, mqttClientId);
            e.printStackTrace();
        }

    }

    @Override
    public String version() {
        return Version.getVersion();
    }

    @Override
    public void start(Map<String, String> map) {
        connectorConfiguration = new MqttSourceConnectorConfig(map);
        connectorName = connectorConfiguration.getString("mqtt.connector.kafka.name");
        kafkaTopic = connectorConfiguration.getString("mqtt.connector.kafka.topic");
        mqttClientId = connectorConfiguration.getString("mqtt.connector.client.id");
        mqttTopic = connectorConfiguration.getString("mqtt.connector.broker.topic");
        logger.info("Starting AsamMqttSourceConnectorTask with connector name: '{}'", connectorName);
        initMqttClient();
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        List<SourceRecord> records = new ArrayList<>();
        records.add(mqttRecordQueue.take());
        return records;
    }

    @Override
    public void stop() {

    }

    @Override
    public void connectionLost(Throwable throwable) {
        logger.error("Connection for connector: '{}', running client: '{}', lost to topic: '{}'.", connectorName, mqttClientId, mqttTopic);
    }

    @Override
    public void messageArrived(String tempMqttTopic, MqttMessage mqttMessage) {
        logger.debug("Mqtt message arrived to connector: '{}', running client: '{}', on topic: '{}'.", connectorName, mqttClientId, tempMqttTopic);
        try {
            logger.debug("Mqtt message payload in byte array: '{}'", mqttMessage.getPayload());
            mqttRecordQueue.put(new SourceRecord(null, null, kafkaTopic, null,
                    Schema.STRING_SCHEMA, makeDBDoc(mqttMessage.getPayload(), tempMqttTopic))
            );
        } catch (Exception e) {
            logger.error("ERROR: Not able to create source record from mqtt message '{}' arrived on topic '{}' for client '{}'.", mqttMessage.toString(), tempMqttTopic, mqttClientId);
            logger.error(e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }

    private String makeDBDoc(byte[] payload, String topic) {
        String msg = new String(payload);

        JSONObject message;

        try {
            message = (JSONObject) jsonParser.parse(msg);
        } catch (org.json.simple.parser.ParseException e) {
            e.printStackTrace();
            message = new JSONObject();
        }


        String now = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        JSONObject processedDate = new JSONObject();
        processedDate.put("topic", topic);
        processedDate.put("message", message);
        processedDate.put("process_date", now);

        return processedDate.toJSONString();
    }
}
