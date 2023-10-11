package com.my.dpdp.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.my.dpdp.Factory;

/**
 * @Author: JunChuang Cai
 * @Date: 2021年5月24日下午4:00:41
 * @Version: 1.0
 * @Description: TODO
 */
public class ReadFactoryCsvUtil {
	private String filePath;

	public ReadFactoryCsvUtil(String filePath) {
		super();
		this.filePath = filePath;
	}

	public HashMap<String, Factory> getFactoryInfo() {
		HashMap<String, Factory> id2factoryMap = new HashMap<String, Factory>();
		try {
			BufferedReader reader = new BufferedReader(new FileReader(filePath));// 换成你的文件名
			reader.readLine();
			String line = null;
			while ((line = reader.readLine()) != null) {
				String item[] = line.split(",");
				String factoryId = item[0];
				double lng = Double.parseDouble(item[1]);
				double lat = Double.parseDouble(item[2]);
				int dockNum = Integer.parseInt(item[3]);
				Factory factory = new Factory(factoryId, lng, lat, dockNum);
				if(!id2factoryMap.containsKey(factoryId)) {
					id2factoryMap.put(factoryId, factory);
				}
//				System.out.println(item[0]+" "+item[1]+" "+item[2]+" "+item[3]+" ");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return id2factoryMap;
	}
}
