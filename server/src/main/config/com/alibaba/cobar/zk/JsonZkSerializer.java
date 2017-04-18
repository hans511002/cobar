package com.alibaba.cobar.zk;

import java.io.IOException;

import org.I0Itec.zkclient.exception.ZkMarshallingError;
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.codehaus.jackson.map.ObjectMapper;

public class JsonZkSerializer implements ZkSerializer {
	public static ObjectMapper objectMapper = new ObjectMapper();

	public JsonZkSerializer() {
	}

	@Override
	public byte[] serialize(Object o) throws ZkMarshallingError {
		try {
			String json = objectMapper.writeValueAsString(o);
			return json.getBytes();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static String serializes(Object o) throws ZkMarshallingError {
		try {
			String json = objectMapper.writeValueAsString(o);
			return json;
		} catch (IOException e) {
			System.err.println("serializes data error:" + o);
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public Object deserialize(byte[] bytes) throws ZkMarshallingError {
		try {
			String json = new String(bytes);
			return objectMapper.readValue(json, Object.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	public static <T> T deserialize(byte[] bytes, Class<T> claz) throws ZkMarshallingError {
		if (bytes == null)
			return null;
		return deserialize(new String(bytes), claz);
	}

	public static <T> T deserialize(String json, Class<T> claz) throws ZkMarshallingError {
		try {
			if (json == null)
				return null;
			// json = json.replaceAll("\\$", "\\$");
			return objectMapper.readValue(json, claz);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}
}
