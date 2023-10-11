package com.my.dpdp;

import java.util.List;

/**
 * @Author: JunChuang Cai
 * @Date: 2021年5月25日下午3:47:19
 * @Version: 1.0
 * @Description: TODO
 */
public class Vehicle {
	private String id;
	private String gpsId;
	private String curFactoryId;
	private int gpsUpdateTime;
	private int operationTime;
	private int boardCapacity;
	private double leftCapacity;
	private int arriveTimeAtCurrentFactory;
	private int leaveTimeAtCurrentFactory;
	private List<OrderItem> carryingItems;
	private List<String> plannedRoute;
	private Node des;

	public Vehicle(String id, String gpsId, int operationTime, int boardCapacity, List<OrderItem> carryingItems) {
		super();
		this.id = id;
		this.gpsId = gpsId;
		this.operationTime = operationTime;
		this.boardCapacity = boardCapacity;
		this.carryingItems = carryingItems;
	}

	public Vehicle(String id, String gpsId, int operationTime, int boardCapacity, List<OrderItem> carryingItems,
			Node des) {
		super();
		this.id = id;
		this.gpsId = gpsId;
		this.operationTime = operationTime;
		this.boardCapacity = boardCapacity;
		this.carryingItems = carryingItems;
		this.des = des;
	}

	public double getLeftCapacity() {
		return leftCapacity;
	}

	public void setLeftCapacity(double leftCapacity) {
		this.leftCapacity = leftCapacity;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getGpsId() {
		return gpsId;
	}

	public void setGpsId(String gpsId) {
		this.gpsId = gpsId;
	}

	public String getCurFactoryId() {
		return curFactoryId;
	}

	public void setCurFactoryId(String curFactoryId) {
		this.curFactoryId = curFactoryId;
	}

	public int getGpsUpdateTime() {
		return gpsUpdateTime;
	}

	public void setGpsUpdateTime(int gpsUpdateTime) {
		this.gpsUpdateTime = gpsUpdateTime;
	}

	public int getOperationTime() {
		return operationTime;
	}

	public void setOperationTime(int operationTime) {
		this.operationTime = operationTime;
	}

	public int getBoardCapacity() {
		return boardCapacity;
	}

	public void setBoardCapacity(int boardCapacity) {
		this.boardCapacity = boardCapacity;
	}

	public int getArriveTimeAtCurrentFactory() {
		return arriveTimeAtCurrentFactory;
	}

	public void setArriveTimeAtCurrentFactory(int arriveTimeAtCurrentFactory) {
		this.arriveTimeAtCurrentFactory = arriveTimeAtCurrentFactory;
	}

	public int getLeaveTimeAtCurrentFactory() {
		return leaveTimeAtCurrentFactory;
	}

	public void setLeaveTimeAtCurrentFactory(int leaveTimeAtCurrentFactory) {
		this.leaveTimeAtCurrentFactory = leaveTimeAtCurrentFactory;
	}

	public List<OrderItem> getCarryingItems() {
		return carryingItems;
	}

	public void setCarryingItems(List<OrderItem> carryingItems) {
		this.carryingItems = carryingItems;
	}

	public List<String> getPlannedRoute() {
		return plannedRoute;
	}

	public void setPlannedRoute(List<String> plannedRoute) {
		this.plannedRoute = plannedRoute;
	}

	public Node getDes() {
		return des;
	}

	public void setDes(Node des) {
		this.des = des;
	}

	public void setCurPositionInfo(String curFactoryId, int updateTime, int arriveTimeAtCurrentFactory,
			int leaveTimeAtCurrentFactory) {
		this.curFactoryId = curFactoryId;
		this.gpsUpdateTime = updateTime;
		if (null != curFactoryId && curFactoryId.length() > 0) {
			this.arriveTimeAtCurrentFactory = arriveTimeAtCurrentFactory;
			this.leaveTimeAtCurrentFactory = leaveTimeAtCurrentFactory;
		} else {
			this.arriveTimeAtCurrentFactory = 0;
			this.leaveTimeAtCurrentFactory = 0;
		}

	}
}
