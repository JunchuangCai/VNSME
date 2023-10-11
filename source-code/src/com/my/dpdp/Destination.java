package com.my.dpdp;

import java.util.List;

/**
 * @Author: JunChuang Cai
 * @Date: 2021年5月22日下午5:21:03
 * @Version: 1.0
 */
public class Destination {
	private String factoryId;
	private List<String> deliveryItemList;
	private List<String> pickupItemList;
	private int arriveTime;
	private int leaveTime;

	public Destination(String factoryId, List<String> deliveryItemList, List<String> pickupItemList, int arriveTime,
			int leaveTime) {
		super();
		this.factoryId = factoryId;
		this.deliveryItemList = deliveryItemList;
		this.pickupItemList = pickupItemList;
		this.arriveTime = arriveTime;
		this.leaveTime = leaveTime;
	}

	public void setFactoryId(String factoryId) {
		this.factoryId = factoryId;
	}

	public String getFactoryId() {
		return factoryId;
	}

	public void setDeliveryItemList(List<String> deliveryItemList) {
		this.deliveryItemList = deliveryItemList;
	}

	public List<String> getDeliveryItemList() {
		return deliveryItemList;
	}

	public void setPickupItemList(List<String> pickupItemList) {
		this.pickupItemList = pickupItemList;
	}

	public List<String> getPickupItemList() {
		return pickupItemList;
	}

	public void setArriveTime(int arriveTime) {
		this.arriveTime = arriveTime;
	}

	public int getArriveTime() {
		return arriveTime;
	}

	public void setLeaveTime(int leaveTime) {
		this.leaveTime = leaveTime;
	}

	public int getLeaveTime() {
		return leaveTime;
	}
}
