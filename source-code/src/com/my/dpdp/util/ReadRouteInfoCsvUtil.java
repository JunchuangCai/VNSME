package com.my.dpdp.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;

/**
 * @Author: JunChuang Cai
 * @Date: 2021年5月24日下午4:00:41
 * @Version: 1.0
 * @Description: TODO
 */
public class ReadRouteInfoCsvUtil {
	private String filePath;

	public ReadRouteInfoCsvUtil(String filePath) {
		super();
		this.filePath = filePath;
	}

	public HashMap<String, String> getRouteInfo() {
		HashMap<String, String> id2routeInfoMap = new HashMap<String, String>();
		try {
			BufferedReader reader = new BufferedReader(new FileReader(filePath));
			reader.readLine();
			String line = null;
			while ((line = reader.readLine()) != null) {
				String item[] = line.split(",");
				String startFactoryId = item[1];
				String endFactoryId = item[2];
				String distance = item[3];
				String time = item[4];
				String key = startFactoryId + "+" + endFactoryId;
				String value = distance + "+" + time;
				if (!id2routeInfoMap.containsKey(key)) {
					id2routeInfoMap.put(key, value);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return id2routeInfoMap;
	}
}
