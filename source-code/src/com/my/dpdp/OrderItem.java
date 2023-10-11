package com.my.dpdp;

/**
 * @Author: JunChuang Cai
 * @Date: 2021年5月22日下午4:09:17
 * @Version: 1.0
 */
public class OrderItem {
	private String id;
	private String type;
	private String orderId;
	private double demand;
	private String pickupFactoryId;
	private String deliveryFactoryId;
	private int creationTime;
	private int committedCompletionTime;
	private int loadTime;
	private int unloadTime;
	private int deliveryState;

	public OrderItem(String id, String type, String orderId, double demand, String pickupFactoryId,
			String deliveryFactoryId, int creationTime, int committedCompletionTime, int loadTime, int unloadTime,
			int deliveryState) {
		super();
		this.id = id;
		this.type = type;
		this.orderId = orderId;
		this.demand = demand;
		this.pickupFactoryId = pickupFactoryId;
		this.deliveryFactoryId = deliveryFactoryId;
		this.creationTime = creationTime;
		this.committedCompletionTime = committedCompletionTime;
		this.loadTime = loadTime;
		this.unloadTime = unloadTime;
		this.deliveryState = deliveryState;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getType() {
		return type;
	}

	public void setOrderId(String orderId) {
		this.orderId = orderId;
	}

	public String getOrderId() {
		return orderId;
	}

	public void setDemand(double demand) {
		this.demand = demand;
	}

	public double getDemand() {
		return demand;
	}

	public void setPickupFactoryId(String pickupFactoryId) {
		this.pickupFactoryId = pickupFactoryId;
	}

	public String getPickupFactoryId() {
		return pickupFactoryId;
	}

	public void setDeliveryFactoryId(String deliveryFactoryId) {
		this.deliveryFactoryId = deliveryFactoryId;
	}

	public String getDeliveryFactoryId() {
		return deliveryFactoryId;
	}

	public int getCreationTime() {
		return creationTime;
	}

	public void setCreationTime(int creationTime) {
		this.creationTime = creationTime;
	}

	public int getCommittedCompletionTime() {
		return committedCompletionTime;
	}

	public void setCommittedCompletionTime(int committedCompletionTime) {
		this.committedCompletionTime = committedCompletionTime;
	}

	public void setLoadTime(int loadTime) {
		this.loadTime = loadTime;
	}

	public int getLoadTime() {
		return loadTime;
	}

	public void setUnloadTime(int unloadTime) {
		this.unloadTime = unloadTime;
	}

	public int getUnloadTime() {
		return unloadTime;
	}

	public void setDeliveryState(int deliveryState) {
		this.deliveryState = deliveryState;
	}

	public int getDeliveryState() {
		return deliveryState;
	}
}
