package com.my.dpdp;
 /**
  * @Author: JunChuang Cai
  * @Date: 2021年5月23日下午7:43:53
  * @Version: 1.0
  * @Description: TODO
  */
public class Factory {
	private String factoryId;
	double lng;
	double lat;
	private int dockNum;
	
	

	public Factory(String factoryId, double lng, double lat, int dockNum) {
		super();
		this.factoryId = factoryId;
		this.lng = lng;
		this.lat = lat;
		this.dockNum = dockNum;
	}

	public double getLng() {
		return lng;
	}

	public void setLng(double lng) {
		this.lng = lng;
	}

	public double getLat() {
		return lat;
	}

	public void setLat(double lat) {
		this.lat = lat;
	}

	public String getFactoryId() {
		return factoryId;
	}

	public void setFactoryId(String factoryId) {
		this.factoryId = factoryId;
	}

	public int getDockNum() {
		return dockNum;
	}

	public void setDockNum(int dockNum) {
		this.dockNum = dockNum;
	}
	
	
}

