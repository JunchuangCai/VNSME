package com.my.dpdp;

import java.util.List;
import java.util.Map;

/**
 * @Author: JunChuang Cai
 * @Date: 2021年5月22日下午4:06:26
 * @Version: 1.0
 * @Description: TODO
 */
public class VehicleInfo {
	private String id;
	private int operationTime;
	private int capacity;
	private String gpsId;
	private long updateTime;
	private String curFactoryId;
	private long arriveTimeAtCurrentFactory;
	private long leaveTimeAtCurrentFactory;
	private List<String> carryingItemsList;
	private Destination destination;


	public VehicleInfo(String id, int operationTime, int capacity, String gpsId, long updateTime, String curFactoryId,
			long arriveTimeAtCurrentFactory, long leaveTimeAtCurrentFactory, List<String> carryingItemsList,
			Destination destination) {
		super();
		this.id = id;
		this.operationTime = operationTime;
		this.capacity = capacity;
		this.gpsId = gpsId;
		this.updateTime = updateTime;
		this.curFactoryId = curFactoryId;
		this.arriveTimeAtCurrentFactory = arriveTimeAtCurrentFactory;
		this.leaveTimeAtCurrentFactory = leaveTimeAtCurrentFactory;
		this.carryingItemsList = carryingItemsList;
		this.destination = destination;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public void setOperationTime(int operationTime) {
		this.operationTime = operationTime;
	}

	public int getOperationTime() {
		return operationTime;
	}

	public void setCapacity(int capacity) {
		this.capacity = capacity;
	}

	public int getCapacity() {
		return capacity;
	}

	public void setGpsId(String gpsId) {
		this.gpsId = gpsId;
	}

	public String getGpsId() {
		return gpsId;
	}

	public void setUpdateTime(long updateTime) {
		this.updateTime = updateTime;
	}

	public long getUpdateTime() {
		return updateTime;
	}

	public void setCurFactoryId(String curFactoryId) {
		this.curFactoryId = curFactoryId;
	}

	public String getCurFactoryId() {
		return curFactoryId;
	}

	public void setArriveTimeAtCurrentFactory(long arriveTimeAtCurrentFactory) {
		this.arriveTimeAtCurrentFactory = arriveTimeAtCurrentFactory;
	}

	public long getArriveTimeAtCurrentFactory() {
		return arriveTimeAtCurrentFactory;
	}

	public void setLeaveTimeAtCurrentFactory(long leaveTimeAtCurrentFactory) {
		this.leaveTimeAtCurrentFactory = leaveTimeAtCurrentFactory;
	}

	public long getLeaveTimeAtCurrentFactory() {
		return leaveTimeAtCurrentFactory;
	}

	public void setCarryingItems(List<String> carryingItemsList) {
		this.carryingItemsList = carryingItemsList;
	}

	public List<String> getCarryingItems() {
		return carryingItemsList;
	}
	public List<String> getCarryingItemsList() {
		return carryingItemsList;
	}

	public void setCarryingItemsList(List<String> carryingItemsList) {
		this.carryingItemsList = carryingItemsList;
	}

	public Destination getDestination() {
		return destination;
	}

	public void setDestination(Destination destination) {
		this.destination = destination;
	}
}

