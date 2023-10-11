package com.my.dpdp;

import java.util.List;

/**
 * @Author: JunChuang Cai
 * @Date: 2021年5月25日下午8:14:37
 * @Version: 1.0
 */
public class Node {
	private String id;// 对应的工厂id
	private List<OrderItem> deliveryItemList;
	private List<OrderItem> pickupItemList;
	private int arriveTime;
	private int leaveTime;
	private int serviceTime;
	private double lng;// 经度
	private double lat;// 纬度

	public Node(String factoryId, List<OrderItem> deliveryItemList, List<OrderItem> pickupItemList, int arriveTime,
			int leaveTime, double lng, double lat) {
		super();
		this.id = factoryId;
		this.deliveryItemList = deliveryItemList;
		this.pickupItemList = pickupItemList;
		this.arriveTime = arriveTime;
		this.leaveTime = leaveTime;
		this.lng = lng;
		this.lat = lat;
		serviceTime = calculateServiceTime();
	}

	public Node(String factoryId, List<OrderItem> deliveryItemList, List<OrderItem> pickupItemList, double lng,
			double lat) {
		super();
		this.id = factoryId;
		this.deliveryItemList = deliveryItemList;
		this.pickupItemList = pickupItemList;
		this.lng = lng;
		this.lat = lat;
		serviceTime = calculateServiceTime();
	}

	public String getId() {
		return id;
	}

	public void setFactoryId(String factoryId) {
		this.id = factoryId;
	}

	public List<OrderItem> getDeliveryItemList() {
		return deliveryItemList;
	}

	public void setDeliveryItemList(List<OrderItem> deliveryItemList) {
		this.deliveryItemList = deliveryItemList;
	}

	public List<OrderItem> getPickupItemList() {
		return pickupItemList;
	}

	public void setPickupItemList(List<OrderItem> pickupItemList) {
		this.pickupItemList = pickupItemList;
	}

	public int getArriveTime() {
		return arriveTime;
	}

	public void setArriveTime(int arriveTime) {
		this.arriveTime = arriveTime;
	}

	public int getLeaveTime() {
		return leaveTime;
	}

	public void setLeaveTime(int leaveTime) {
		this.leaveTime = leaveTime;
	}

	public int getServiceTime() {
		return serviceTime;
	}

	public void setServiceTime(int serviceTime) {
		this.serviceTime = serviceTime;
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

	private int calculateServiceTime() {
		// 获取装载时间
		int loadingTime = 0;
		if (null != pickupItemList && pickupItemList.size() > 0) {
			for (OrderItem orderItem : pickupItemList) {
				loadingTime += orderItem.getLoadTime();
			}
		}

		int unloadingTime = 0;
		if (null != deliveryItemList && deliveryItemList.size() > 0) {
			for (OrderItem orderItem : deliveryItemList) {
				unloadingTime += orderItem.getUnloadTime();
			}
		}
		return loadingTime + unloadingTime;
	}
}
