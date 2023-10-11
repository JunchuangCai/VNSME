
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.json.CDL;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.my.dpdp.Destination;
import com.my.dpdp.Factory;
import com.my.dpdp.Node;
import com.my.dpdp.OrderItem;
import com.my.dpdp.Param;
import com.my.dpdp.Vehicle;
import com.my.dpdp.VehicleInfo;
import com.my.dpdp.util.ReadFactoryCsvUtil;
import com.my.dpdp.util.ReadRouteInfoCsvUtil;

/**
 * @Author: JunChuang Cai
 * @Date: 2021年5月24日下午3:47:58
 * @Version: 1.0
 * @Description: TODO
 */
public class main_algorithm {
	private static String input_directory;
	private static String output_directory;
	private static HashMap<String, Factory> id2FactoryMap;
	private static HashMap<String, String> id2RouteMap;
	private static List<VehicleInfo> vehicleInfoList = new ArrayList<>();
	private static LinkedHashMap<String, OrderItem> id2UnallocatedOrderMap = new LinkedHashMap<>();
	private static LinkedHashMap<String, OrderItem> id2OngoingOrderMap = new LinkedHashMap<>();
	private static LinkedHashMap<String, OrderItem> id2OrderItemMap;
	private static LinkedHashMap<String, Vehicle> id2VehicleMap = new LinkedHashMap<>();
	private static LinkedHashMap<String, Node> vehicleId2Destination = new LinkedHashMap<>();
	private static LinkedHashMap<String, ArrayList<Node>> vehicleId2PlannedRoute = new LinkedHashMap<>();
	private static Node destination;
	private static final String toMemoryPath = "./algorithm/memory";
	private static final int APPROACHING_DOCK_TIME = 1800;
	private static final double Delta = 10000.0 / 3600.0;
	private static final double Delta1 = 10000.0;

	private static final boolean isDelay = true;
	private static final int SLACK_TIME_THRESHOLD = 14400 - 600 * 9;

	private static String deltaT;
	private static String completeOrderItems = "";
	private static String newOrderItems;
	private static String onVehicleOrderItems;
	private static String unallocatedOrderItems;
	private static String routeBefore = "";
	private static ArrayList<String> preMatchingItemIds;
	private static final boolean isDebug = false;
	private static final boolean isTest = false;

	private static final String debugPeriod = "0010-0020";
	private static int[] emergencyIndex;
	private static double usedTime;
	private static String bestInsertVehicleId = null;
	private static int bestInsertPosI = 0, bestInsertPosJ = 1;
	private static double minCostDelta;
	private static final int Nmax = 10;
	private static HashMap<String, ArrayList<ArrayList<Integer>>> id2DockTableMap = new HashMap<String, ArrayList<ArrayList<Integer>>>();
	private static boolean isExhaustive = false;
	private static ArrayList<Node> bestNodeList;
	private static long begintime;
	private static double addDelta = 300000.0;
	private static final double Alpha = 6.0;
	private static final int LIMIT_TIME = 8;

	private static double beforeCost;

	public static void main(String[] args) throws IOException {
		if (isTest) {
			input_directory = "./algorithm/memory";
		} else {
			input_directory = "./algorithm/data_interaction";
		}
		begintime = System.nanoTime();

		readInputFile();
		dealOldSolutionFile();
		restoreSceneWithSingleNode();
		if (isDebug && !isTest) {
			copyInputFile();
			copyInputFile2XLS();
		}
		if (isOver24Hour()) {
			redispatchProcess();
		} else {
			dispatchNewOrders();
		}
		variableNeighbourhoodSearch();

		updateSolutionJson();
		MergeNode();
		if (isDelay) {
			outputJsonWithDelayDispatchTime();
		} else {
			outputJson();
		}
		long endtime = System.nanoTime();
		usedTime = (endtime - begintime) / (1e9);

		System.out.println("SUCCESS");
	}

	/**
	 * 对超过24小时的订单进行重新分派，若比恢复解的cost小则替换
	 */
	private static void redispatchProcess() {
		double cost0 = cost();
		String orderItemsIdStr = "";
		LinkedHashMap<String, ArrayList<Node>> backupRestoreSolution = new LinkedHashMap<>();
		for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
			String vehicleId = id2NodesMap.getKey();
			Vehicle vehicle = id2VehicleMap.get(vehicleId);
			ArrayList<Node> id2NodeList = id2NodesMap.getValue();
			int nodeSize = id2NodeList == null ? 0 : id2NodeList.size();
			int beginPos = 0;
			if (nodeSize == 0) {
				backupRestoreSolution.put(vehicleId, null);
				continue;
			}
			ArrayList<Node> cpNodeList = new ArrayList<>(id2NodeList);
			backupRestoreSolution.put(vehicleId, cpNodeList);
			if (vehicle.getDes() != null) {
				beginPos = 1;
			}
			for (int i = beginPos; i < id2NodeList.size(); i++) {
				Node node1 = id2NodeList.get(i);
				if (node1.getPickupItemList() != null && !node1.getPickupItemList().isEmpty()) {
					String beginItemId = node1.getPickupItemList().get(0).getId();
					for (int j = i + 1; j < id2NodeList.size(); j++) {
						Node node2 = id2NodeList.get(j);
						int dLen = node2.getDeliveryItemList() == null ? 0 : node2.getDeliveryItemList().size();
						if (node2.getDeliveryItemList() != null && !node2.getDeliveryItemList().isEmpty()
								&& node2.getDeliveryItemList().get(dLen - 1).getId().equals(beginItemId)) {
							for (OrderItem orderItem : node1.getPickupItemList()) {
								orderItemsIdStr += orderItem.getId() + " ";
							}
							id2NodeList.remove(i);
							id2NodeList.remove(j - 1);
							i--;
							break;

						}
					}
				}
			}
		}
		orderItemsIdStr = orderItemsIdStr.trim();
		if (orderItemsIdStr.length() > 0) {
			ArrayList<String> orderItemIdList = new ArrayList<String>(Arrays.asList(orderItemsIdStr.split(" ")));
			Collections.sort(orderItemIdList);
			Collections.sort(orderItemIdList, new Comparator<String>() {
				@Override 
				public int compare(String str1, String str2) {
					String[] str1Items = null;
					String[] str2Items = null;
					str1Items = str1.split("-");
					str2Items = str2.split("-");
					int result = 0;
					String a1 = str1Items[0];
					int a2 = Integer.valueOf(str1Items[1]);
					String b1 = str2Items[0];
					int b2 = Integer.valueOf(str2Items[1]);
					if (!a1.equals(b1)) {
						return a1.compareTo(b1);
					} else {
						return a2 - b2;
					}
				}
			});
			newOrderItems += " ";
			for (String itemId : orderItemIdList) {
				newOrderItems += itemId + " ";
			}
			newOrderItems = newOrderItems.trim();
		}
		dispatchNewOrders();
		double cost1 = cost();
		if (cost1 + 0.01 < cost0) {
			vehicleId2PlannedRoute = backupRestoreSolution;
		} else {
			System.err.println("After 24h,redispatch valid.originCost:" + cost0 + ",newCost:" + cost1
					+ ",improve value:" + (cost0 - cost1));
		}
	}

	/* VNS:用于改进CI得到的解 */

	private static void variableNeighbourhoodSearch() {
		int n1 = 0, n2 = 0, n3 = 0, n4 = 0, n5 = 0;
		long endtime = System.nanoTime();
		usedTime = (endtime - begintime) / (1e9);
		while (true) {

			if (interCoupleExchange()) {
				n1 = n1 + 1;
				continue;
			}

			endtime = System.nanoTime();
			usedTime = (endtime - begintime) / (1e9);
			if (usedTime > LIMIT_TIME * 60) {
				System.err.println("TimeOut!!");
				break;
			}

			if (blockExchange()) {
				n2 = n2 + 1;
				continue;
			}

			endtime = System.nanoTime();
			usedTime = (endtime - begintime) / (1e9);
			if (usedTime > LIMIT_TIME * 60) {
				System.err.println("TimeOut!!");
				break;
			}

			if (blockRelocate()) {
				n3 = n3 + 1;
				continue;
			}
			endtime = System.nanoTime();
			usedTime = (endtime - begintime) / (1e9);
			if (usedTime > LIMIT_TIME * 60) {
				System.err.println("TimeOut!!");
				break;
			}
			if (multiPDGroupRelocate()) {
				n4 = n4 + 1;
			} else {
				if (!improveCIPathBy2Opt()) {
					break;
				}
				n5 = 0;
			}

			endtime = System.nanoTime();
			usedTime = (endtime - begintime) / (1e9);
			if (usedTime > LIMIT_TIME * 60) {
				System.err.println("TimeOut!!");
				break;
			}
		}
	}

	/**
	 * 用2-opt提升一条路径
	 * 
	 * @param tempRouteNodeList
	 * @param beginPos
	 * @param vehicle
	 */
	private static boolean improveCIPathBy2Opt() {
		boolean isImproved = false;
		double cost0 = cost();
		double originCost = cost0;
		for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
			String vehicleId = id2NodesMap.getKey();
			Vehicle vehicle = id2VehicleMap.get(vehicleId);
			ArrayList<Node> routeNodeList = id2NodesMap.getValue();
			int routeLen = routeNodeList == null ? 0 : routeNodeList.size();
			if (routeLen == 0) {
				continue;
			}
			int beginPos = 0;
			if (vehicle.getDes() != null) {
				beginPos = 1;
			}
			boolean isRouteImproved = false;
			boolean[][] REV = Check(routeNodeList, beginPos);
			minCostDelta = Double.MAX_VALUE;
			for (int i = beginPos; i < routeLen - 3; i++) {
				for (int j = i + 2; j < routeLen; j++) {
					ArrayList<Node> tempRouteNodeList = new ArrayList<>(routeNodeList);
					Node nodeI = tempRouteNodeList.get(i);
					Node nodeIPlus = tempRouteNodeList.get(i + 1);
					Node nodeJ = tempRouteNodeList.get(j);
					/* case 1: Si=z+ && Si+1=z− */
					int dLen = nodeIPlus.getDeliveryItemList() == null ? 0 : nodeIPlus.getDeliveryItemList().size();
					if (nodeI.getPickupItemList() != null && nodeIPlus.getDeliveryItemList() != null
							&& !nodeI.getPickupItemList().isEmpty() && !nodeIPlus.getDeliveryItemList().isEmpty()
							&& nodeI.getPickupItemList().get(0).getId()
									.equals(nodeIPlus.getDeliveryItemList().get(dLen - 1).getId())) {
						int posK = isOverlapped(tempRouteNodeList, i);
						if (posK == 0 || posK >= j + 1) {
							if (!REV[i + 2][j]) {
								continue;
							} else {
								ArrayList<Node> temp = new ArrayList<>(tempRouteNodeList.subList(i + 2, j + 1));
								int reserveLen = temp.size();
								tempRouteNodeList.subList(i + 2, j + 1).clear();
								tempRouteNodeList.addAll(i + 1, temp);
								tempRouteNodeList = Reverse(tempRouteNodeList, i + 1, i + reserveLen, vehicle);
								if (tempRouteNodeList == null) {
									continue;
								}
								double cost = cost(tempRouteNodeList, vehicle);
								if (cost < minCostDelta) {
									System.err.println("tried case 1 improved");
									minCostDelta = cost;
									bestNodeList = new ArrayList<>(tempRouteNodeList);
								}
							}
						} else if (posK <= j) {
							break;
						}
					}
					/* case 2: Bz(Si, Sk) and i + 1 < k < j */
					int k = getBlockRightBound(tempRouteNodeList, i);
					if (k == routeLen - 1) {
						break;
					}
					if (i + 1 < k && k < j) {
						if (!REV[k + 1][j]) {
							continue;
						} else {
							ArrayList<Node> temp = new ArrayList<>(tempRouteNodeList.subList(k + 1, j + 1));
							tempRouteNodeList.subList(k + 1, j + 1).clear();
							tempRouteNodeList.addAll(i + 1, temp);
							tempRouteNodeList = Reverse(tempRouteNodeList, i + 1, i + j - k, vehicle);
							if (tempRouteNodeList == null) {
								continue;
							}
							double cost = cost(tempRouteNodeList, vehicle);
							if (cost < minCostDelta) {
								System.err.println("tried case 2 improved");
								minCostDelta = cost;
								bestNodeList = new ArrayList<>(tempRouteNodeList);
							}
						}
					}
					/* case 3: Bz(Si, Sk) and k ≥ j */
					if (k >= j) {
						continue;
					}
					/* case 4: Si= z− */
					if (tempRouteNodeList.get(i).getDeliveryItemList() != null
							&& !tempRouteNodeList.get(i).getDeliveryItemList().isEmpty()) {
						if (!REV[i + 2][j]) {
							continue;
						}
						ArrayList<Node> temp = new ArrayList<>(tempRouteNodeList.subList(i + 2, j + 1));
						int reserveLen = temp.size();
						tempRouteNodeList.subList(i + 2, j + 1).clear();
						tempRouteNodeList.addAll(i + 1, temp);
						tempRouteNodeList = Reverse(tempRouteNodeList, i + 1, i + reserveLen, vehicle);
						if (tempRouteNodeList == null) {
							continue;
						}
						double cost = cost(tempRouteNodeList, vehicle);
						if (cost < minCostDelta) {
							System.err.println("tried case 4 improved");
							minCostDelta = cost;
							bestNodeList = new ArrayList<>(tempRouteNodeList);
						}
					}
				}
			}
			if (minCostDelta < cost0 * Alpha) {
				isImproved = true;
				isRouteImproved = true;
			}
			if (isRouteImproved) {
				vehicleId2PlannedRoute.put(vehicleId, bestNodeList);
			}
			double endtime = System.nanoTime();
			usedTime = (endtime - begintime) / (1e9);
			if (usedTime > LIMIT_TIME * 60) {
				System.err.println("TimeOut!!");
				return isImproved;
			}
		}

		return isImproved;
	}

	/**
	 * 若i对应的节点是送货节点，则返回-1；若是取货节点，则返回Bi右边界。
	 * 
	 * @param tempRouteNodeList
	 * @param i
	 * @return
	 */
	private static int getBlockRightBound(ArrayList<Node> tempRouteNodeList, int i) {
		int idx = -1;
		if (tempRouteNodeList.get(i).getDeliveryItemList() != null
				&& !tempRouteNodeList.get(i).getDeliveryItemList().isEmpty()) {
			return idx;
		} else {
			String orderItem0Id = tempRouteNodeList.get(i).getPickupItemList().get(0).getId();
			for (int k = i + 1; k < tempRouteNodeList.size(); k++) {
				if (tempRouteNodeList.get(k).getDeliveryItemList() != null
						&& !tempRouteNodeList.get(k).getDeliveryItemList().isEmpty()) {
					int dLen = tempRouteNodeList.get(k).getDeliveryItemList().size();
					if (orderItem0Id.equals(tempRouteNodeList.get(k).getDeliveryItemList().get(dLen - 1).getId())) {
						idx = k;
						break;
					}
				}
			}
		}
		return idx;
	}

	/**
	 * SUP(Bz) = ∅时，返回0，否则返回SUP(Bz)最右端的idx
	 * 
	 * @param tempRouteNodeList
	 * @param i
	 * @return
	 */
	private static int isOverlapped(ArrayList<Node> tempRouteNodeList, int i) {
		ArrayList<Node> heap = new ArrayList<>();
		for (int k = 0; k < i; k++) {
			if (tempRouteNodeList.get(k).getPickupItemList() != null
					&& !tempRouteNodeList.get(k).getPickupItemList().isEmpty()) {
				heap.add(0, tempRouteNodeList.get(k));
			} else {
				int dLen = tempRouteNodeList.get(k).getDeliveryItemList().size();
				if (heap.size() > 0 && tempRouteNodeList.get(k).getDeliveryItemList().get(dLen - 1).getId()
						.equals(heap.get(0).getPickupItemList().get(0).getId())) {
					heap.remove(0);
				}
			}
		}
		int idx = 0;
		int k = i + 2;
		if (heap.size() > 0) {
			for (; k < tempRouteNodeList.size(); k++) {
				if (tempRouteNodeList.get(k).getDeliveryItemList() != null
						&& !tempRouteNodeList.get(k).getDeliveryItemList().isEmpty()) {
					int dLen = tempRouteNodeList.get(k).getDeliveryItemList().size();
					if (heap.get(0).getPickupItemList().get(0).getId()
							.equals(tempRouteNodeList.get(k).getDeliveryItemList().get(dLen - 1).getId())) {
						idx = k;
						heap.remove(0);
						if (heap.size() == 0) {
							k++;
							break;
						}
					}
				}
			}
		}
		for (; k < tempRouteNodeList.size(); k++) {
			if (tempRouteNodeList.get(k).getDeliveryItemList() != null
					&& !tempRouteNodeList.get(k).getDeliveryItemList().isEmpty()) {
				idx++;
			} else {
				break;
			}
		}
		return idx;
	}

	/**
	 * 以block为单位翻转，注：只有符合翻转的序列才能调用这个函数
	 * 
	 * @param tempNodeList
	 * @param beginPos
	 * @param endPos
	 * @param vehicle
	 * @return resultNodeList
	 */
	private static ArrayList<Node> Reverse(ArrayList<Node> tempNodeList, int beginPos, int endPos, Vehicle vehicle) {
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> unongoingSuperNode = new LinkedHashMap<>();
		String vehicleId = vehicle.getId();
		int lsNodePairNum = 0;
		/************ step 1. 获取一条路径中的所有PGD ************/
		if (tempNodeList != null && tempNodeList.size() > 0) {
			ArrayList<Node> pickupNodeHeap = new ArrayList<Node>();
			ArrayList<Integer> pNodeIdxHeap = new ArrayList<>();
			LinkedHashMap<String, Node> PAndDNodeMap = new LinkedHashMap<>();
			int idx = 0;
			String beforePFactoryId = null, beforeDFactoryId = null;
			int beforePNodeIdx = 0, beforeDNodeIdx = 0;
			for (int i = beginPos; i <= endPos; i++) {
				Node node = tempNodeList.get(i);
				if (node.getDeliveryItemList() != null && !node.getDeliveryItemList().isEmpty()
						&& node.getPickupItemList() != null && !node.getPickupItemList().isEmpty()) {
					System.err.println("Exist combine Node exception when LS");
					System.exit(0);
				}
				String heapTopOrderItemId = pickupNodeHeap.isEmpty() ? ""
						: pickupNodeHeap.get(0).getPickupItemList().get(0).getId();
				if (node.getDeliveryItemList() != null && node.getDeliveryItemList().size() > 0) {
					int len = node.getDeliveryItemList().size();
					if (node.getDeliveryItemList().get(len - 1).getId().equals(heapTopOrderItemId)) {
						String pickupNodeKey = vehicleId + "," + String.valueOf(pNodeIdxHeap.get(0));
						String deliveryNodeKey = vehicleId + "," + String.valueOf(i);
						if (PAndDNodeMap.size() >= 2) {
							if (!pickupNodeHeap.get(0).getId().equals(beforePFactoryId)
									|| pNodeIdxHeap.get(0) + 1 != beforePNodeIdx
									|| !node.getId().equals(beforeDFactoryId) || i - 1 != beforeDNodeIdx) {
								int firstPNodeIdx = getFirstPNodeIdx(PAndDNodeMap);
								unongoingSuperNode.put(firstPNodeIdx, PAndDNodeMap);
								PAndDNodeMap = new LinkedHashMap<>();
							}
						}
						PAndDNodeMap.put(pickupNodeKey, pickupNodeHeap.get(0));
						PAndDNodeMap.put(deliveryNodeKey, node);
						beforePFactoryId = pickupNodeHeap.get(0).getId();
						beforePNodeIdx = pNodeIdxHeap.get(0);
						beforeDFactoryId = node.getId();
						beforeDNodeIdx = i;
						pickupNodeHeap.remove(0);
						pNodeIdxHeap.remove(0);
					}
				}
				if (node.getPickupItemList() != null && node.getPickupItemList().size() > 0) {
					pickupNodeHeap.add(0, node);
					pNodeIdxHeap.add(0, i);
					if (!PAndDNodeMap.isEmpty()) {
						int firstPNodeIdx = getFirstPNodeIdx(PAndDNodeMap);
						unongoingSuperNode.put(firstPNodeIdx, PAndDNodeMap);
						PAndDNodeMap = new LinkedHashMap<>();
					}
				}
			}
			if (PAndDNodeMap.size() >= 2) {
				int firstPNodeIdx = getFirstPNodeIdx(PAndDNodeMap);
				unongoingSuperNode.put(firstPNodeIdx, PAndDNodeMap);
			}
		}
		if (unongoingSuperNode.size() < 2) {
			return null;
		}
		List<Map.Entry<Integer, LinkedHashMap<String, Node>>> sortMap = new ArrayList<Map.Entry<Integer, LinkedHashMap<String, Node>>>(
				unongoingSuperNode.entrySet());
		Collections.sort(sortMap, new Comparator<Map.Entry<Integer, LinkedHashMap<String, Node>>>() {
			public int compare(Map.Entry<Integer, LinkedHashMap<String, Node>> o1,
					Map.Entry<Integer, LinkedHashMap<String, Node>> o2) {
				int k1 = o1.getKey();
				int k2 = o2.getKey();
				return k1 - k2;
			}
		});
		unongoingSuperNode.clear();
		for (Map.Entry<Integer, LinkedHashMap<String, Node>> entity : sortMap) {
			unongoingSuperNode.put(entity.getKey(), entity.getValue());
		}

		/************
		 * step 2. 对乱序的unongoingSuperNode(eg:p3d3p2d2)进行规格化还原为p2p3d3d2
		 ************/
		int beforeBlockI = -1, beforeBlockJ = -1;
		LinkedHashMap<String, ArrayList<Node>> blockMap = new LinkedHashMap<String, ArrayList<Node>>();
		for (Entry<Integer, LinkedHashMap<String, Node>> pdgMap : unongoingSuperNode.entrySet()) {
			Integer idx = pdgMap.getKey();
			LinkedHashMap<String, Node> pdg = pdgMap.getValue();
			Node pickupNode, deliveryNode;
			ArrayList<Node> nodeList = new ArrayList<>();
			int posI = 0, posJ = 0;
			int dNum = pdg.size() / 2;
			int index = 0;

			if (null != pdg) {
				for (Entry<String, Node> nodeInfo : pdg.entrySet()) {
					String vAndPosStr = nodeInfo.getKey();
					Node node = nodeInfo.getValue();
					if (index % 2 == 0) {
						vehicleId = vAndPosStr.split(",")[0];
						posI = Integer.valueOf(vAndPosStr.split(",")[1]);
						pickupNode = node;
						nodeList.add(0, pickupNode);
						index++;
					} else {
						posJ = Integer.valueOf(vAndPosStr.split(",")[1]);
						deliveryNode = node;
						nodeList.add(deliveryNode);
						index++;
						posJ = posJ - dNum + 1;
					}
				}
				if (posI > beforeBlockJ) {
					for (int i = posI + dNum; i < posJ; i++) {
						nodeList.add(i - posI, tempNodeList.get(i));
					}
					String k = vehicleId + "," + String.valueOf(posI) + "+" + String.valueOf(posJ + dNum - 1);
					blockMap.put(k, nodeList);
					beforeBlockI = posI;
					beforeBlockJ = posJ + dNum - 1;
				}
			}
		}
		if (blockMap.size() < 2) {
			return null;
		}

		/************ step 3. 返回p(si,sj)以block为单位逆序的路径 ************/
		ArrayList<Node> resultNodeList = new ArrayList<>();
		ArrayList<Node> reverseBlockNodeList = new ArrayList<>();
		for (Entry<String, ArrayList<Node>> blockMapItem : blockMap.entrySet()) {
			ArrayList<Node> block = blockMapItem.getValue();
			reverseBlockNodeList.addAll(0, block);
		}
		for (int i = 0; i < beginPos; i++) {
			resultNodeList.add(tempNodeList.get(i));
		}
		for (Node node : reverseBlockNodeList) {
			resultNodeList.add(node);
		}
		for (int i = endPos + 1; i < tempNodeList.size(); i++) {
			resultNodeList.add(tempNodeList.get(i));
		}
		return resultNodeList;
	}

	/**
	 * 获取一个乱序的pAndDNodeMap(eg:p3d3p2d2)的最左边的p的下标，pAndDNodeMap=p3d3p2d2时则返回pos(p2)
	 * 
	 * @param pAndDNodeMap
	 * @return
	 */
	private static int getFirstPNodeIdx(LinkedHashMap<String, Node> pAndDNodeMap) {
		String firstKey = pAndDNodeMap.entrySet().iterator().next().getKey();
		int pLen = pAndDNodeMap.size() / 2;
		int firstPNodeIdx = Integer.valueOf(firstKey.split(",")[1]) - pLen + 1;
		return firstPNodeIdx;
	}

	/**
	 * 计算出当前路径的所有2-exchange
	 * 
	 * @param tempRouteNodeList
	 * @param beginPos          第一个节点可能已经被车辆锁定了
	 * @return
	 */
	private static boolean[][] Check(ArrayList<Node> tempRouteNodeList, int beginPos) {
		int routeLen = tempRouteNodeList.size();
		boolean[][] isFeasible = new boolean[routeLen][routeLen];
		for (int i = beginPos; i < routeLen - 1; i++) {
			Node firstNode = tempRouteNodeList.get(i);
			if (firstNode.getDeliveryItemList() != null && !firstNode.getDeliveryItemList().isEmpty()) {
				continue;
			}
			for (int j = i + 1; j < routeLen; j++) {
				Node lastNode = tempRouteNodeList.get(j);
				if (lastNode.getPickupItemList() != null && !lastNode.getPickupItemList().isEmpty()) {
					continue;
				}
				ArrayList<Node> nodeList = new ArrayList<>();
				boolean isDNodeRedundant = false;
				for (int k = i; k <= j; k++) {
					Node node = tempRouteNodeList.get(k);
					if (node.getPickupItemList() != null && !node.getPickupItemList().isEmpty()) {
						nodeList.add(0, node);
					} else {
						int dLen = node.getDeliveryItemList().size();
						if (!nodeList.isEmpty() && nodeList.get(0).getPickupItemList() != null
								&& !nodeList.get(0).getPickupItemList().isEmpty() && nodeList.get(0).getPickupItemList()
										.get(0).getId().equals(node.getDeliveryItemList().get(dLen - 1).getId())) {
							nodeList.remove(0);
						} else {
							isDNodeRedundant = true;
							break;
						}
					}
				}
				if (nodeList.isEmpty() && !isDNodeRedundant) {
					isFeasible[i][j] = true;
				}
			}
		}
		return isFeasible;
	}

	/**
	 * block-relocate operator
	 * 
	 * @return
	 */
	private static boolean blockRelocate() {
		boolean isImproved = false;
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> disOrderSuperNode = getUnongoingSuperNode();
		int lsNodePairNum = disOrderSuperNode.size();
		if (lsNodePairNum == 0) {
			return false;
		}
		String vehicleId = null;
		LinkedHashMap<String, ArrayList<Node>> blockMap = new LinkedHashMap<String, ArrayList<Node>>();
		for (Entry<Integer, LinkedHashMap<String, Node>> pdgMap : disOrderSuperNode.entrySet()) {
			Integer idx = pdgMap.getKey();
			LinkedHashMap<String, Node> pdg = pdgMap.getValue();
			Node pickupNode, deliveryNode;
			ArrayList<Node> nodeList = new ArrayList<>();
			int posI = 0, posJ = 0;
			int dNum = pdg.size() / 2;
			int index = 0;
			if (null != pdg) {
				for (Entry<String, Node> nodeInfo : pdg.entrySet()) {
					String vAndPosStr = nodeInfo.getKey();
					Node node = nodeInfo.getValue();
					if (index % 2 == 0) {
						vehicleId = vAndPosStr.split(",")[0];
						posI = Integer.valueOf(vAndPosStr.split(",")[1]);
						pickupNode = node;
						nodeList.add(0, pickupNode);
						index++;
					} else {
						posJ = Integer.valueOf(vAndPosStr.split(",")[1]);
						deliveryNode = node;
						nodeList.add(deliveryNode);
						index++;
						posJ = posJ - dNum + 1;
					}
				}
				ArrayList<Node> vehicleNodeRoute = vehicleId2PlannedRoute.get(vehicleId);
				for (int i = posI + dNum; i < posJ; i++) {
					nodeList.add(i - posI, vehicleNodeRoute.get(i));
				}
				String k = vehicleId + "," + String.valueOf(posI) + "+" + String.valueOf(posJ + dNum - 1);
				blockMap.put(k, nodeList);
			}
		}
		double originCost = cost();
		double minCost = originCost;
		String bestRelocateVehicleId = null;
		int bestRelocatePos = 0;
		String minCostBlock1KeyStr = null;
		ArrayList<Node> bestRelocateBlock = null;
		for (Entry<String, ArrayList<Node>> blockItem : blockMap.entrySet()) {
			String beforeKey = blockItem.getKey();
			String beforeVid = beforeKey.split(",")[0];
			int beforePostI = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[0]);
			int beforePostJ = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[1]);
			Vehicle beforeVehicle = id2VehicleMap.get(beforeVid);
			ArrayList<Node> beforeBlock = blockItem.getValue();
			int block1Len = beforeBlock.size();
			ArrayList<Node> routeNodeList1 = vehicleId2PlannedRoute.get(beforeVid);
			routeNodeList1.subList(beforePostI, beforePostI + block1Len).clear();
			ArrayList<Node> routeNodeList;
			int index = 0;
			for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
				vehicleId = "V_" + String.valueOf(++index);
				Vehicle vehicle = id2VehicleItem.getValue();
				routeNodeList = vehicleId2PlannedRoute.get(vehicleId);
				int nodeListSize = routeNodeList == null ? 0 : routeNodeList.size();
				if (nodeListSize == 0) {
					routeNodeList = new ArrayList<Node>();
				}
				int insertPos = 0;
				if (vehicle.getDes() != null) {
					insertPos = 1;
				}
				for (int i = insertPos; i <= nodeListSize; i++) {
					routeNodeList.addAll(i, beforeBlock);
					double cost = cost(routeNodeList, vehicle);
					if (cost < minCost) {
						minCost = cost;
						isImproved = true;
						minCostBlock1KeyStr = beforeKey;
						bestRelocateBlock = new ArrayList<>(beforeBlock);
						bestRelocateVehicleId = vehicleId;
						bestRelocatePos = i;
					}
					routeNodeList.subList(i, i + block1Len).clear();
				}
			}
			routeNodeList1.addAll(beforePostI, beforeBlock);
		}

		if (isImproved) {
			String beforeVid = minCostBlock1KeyStr.split(",")[0];
			int beforePostI = Integer.valueOf(minCostBlock1KeyStr.split(",")[1].split("\\+")[0]);
			int beforePostJ = Integer.valueOf(minCostBlock1KeyStr.split(",")[1].split("\\+")[1]);
			ArrayList<Node> originRouteNodeList = vehicleId2PlannedRoute.get(beforeVid);
			originRouteNodeList.subList(beforePostI, beforePostI + bestRelocateBlock.size()).clear();
			ArrayList<Node> bestRelocateRoute = vehicleId2PlannedRoute.get(bestRelocateVehicleId);
			if (bestRelocateRoute == null) {
				bestRelocateRoute = new ArrayList<>();
			}
			bestRelocateRoute.addAll(bestRelocatePos, bestRelocateBlock);
			vehicleId2PlannedRoute.put(bestRelocateVehicleId, bestRelocateRoute);
		}
		return isImproved;
	}

	/**
	 * 块交换
	 * 
	 * @return
	 */
	private static boolean blockExchange() {
		boolean isImproved = false;
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> disOrderSuperNode = getUnongoingSuperNode();
		int lsNodePairNum = disOrderSuperNode.size();
		if (lsNodePairNum == 0) {
			return false;
		}

		String vehicleId = null;
		LinkedHashMap<String, ArrayList<Node>> blockMap = new LinkedHashMap<String, ArrayList<Node>>();
		for (Entry<Integer, LinkedHashMap<String, Node>> pdgMap : disOrderSuperNode.entrySet()) {
			Integer idx = pdgMap.getKey();
			LinkedHashMap<String, Node> pdg = pdgMap.getValue();
			Node pickupNode, deliveryNode;
			ArrayList<Node> nodeList = new ArrayList<>();
			int posI = 0, posJ = 0;
			int dNum = pdg.size() / 2;
			int index = 0;
			if (null != pdg) {
				for (Entry<String, Node> nodeInfo : pdg.entrySet()) {
					String vAndPosStr = nodeInfo.getKey();
					Node node = nodeInfo.getValue();
					if (index % 2 == 0) {
						vehicleId = vAndPosStr.split(",")[0];
						posI = Integer.valueOf(vAndPosStr.split(",")[1]);
						pickupNode = node;
						nodeList.add(0, pickupNode);
						index++;
					} else {
						posJ = Integer.valueOf(vAndPosStr.split(",")[1]);
						deliveryNode = node;
						nodeList.add(deliveryNode);
						index++;
						posJ = posJ - dNum + 1;
					}
				}
				ArrayList<Node> vehicleNodeRoute = vehicleId2PlannedRoute.get(vehicleId);
				for (int i = posI + dNum; i < posJ; i++) {
					nodeList.add(i - posI, vehicleNodeRoute.get(i));
				}
				String k = vehicleId + "," + String.valueOf(posI) + "+" + String.valueOf(posJ + dNum - 1);
				blockMap.put(k, nodeList);
			}
		}
		if (blockMap.size() < 2) {
			return false;
		}
		double originCost = cost();
		double minCost = originCost;
		String minCostBlock1KeyStr = null, minCostBlock2KeyStr = null;
		ArrayList<Node> minCostBlock1 = null, minCostBlock2 = null;
		int idxI = 0, idxJ = 0;
		for (Entry<String, ArrayList<Node>> blockMap1 : blockMap.entrySet()) {
			String beforeKey = blockMap1.getKey();
			String beforeVid = beforeKey.split(",")[0];
			int beforePostI = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[0]);
			int beforePostJ = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[1]);
			Vehicle beforeVehicle = id2VehicleMap.get(beforeVid);
			ArrayList<Node> beforeBlock = blockMap1.getValue();
			int block1Len = beforeBlock.size();
			idxJ = 0;
			for (Entry<String, ArrayList<Node>> blockMap2 : blockMap.entrySet()) {
				if (idxI >= idxJ) {
					idxJ++;
					continue;
				}

				String nextKey = blockMap2.getKey();
				String nextVid = nextKey.split(",")[0];
				int nextPostI = Integer.valueOf(nextKey.split(",")[1].split("\\+")[0]);
				int nextPostJ = Integer.valueOf(nextKey.split(",")[1].split("\\+")[1]);
				Vehicle nextVehicle = id2VehicleMap.get(nextVid);
				ArrayList<Node> nextBlock = blockMap2.getValue();
				int block2Len = nextBlock.size();
				ArrayList<Node> routeNodeList1 = vehicleId2PlannedRoute.get(beforeVid);

				if (!beforeVid.equals(nextVid)) {
					ArrayList<Node> routeNodeList2 = vehicleId2PlannedRoute.get(nextVid);
					List<Node> temp1 = new ArrayList<Node>(
							routeNodeList1.subList(beforePostI, beforePostI + block1Len));
					List<Node> temp2 = new ArrayList<Node>(routeNodeList2.subList(nextPostI, nextPostI + block2Len));
					routeNodeList1.subList(beforePostI, beforePostI + block1Len).clear();
					routeNodeList2.subList(nextPostI, nextPostI + block2Len).clear();
					routeNodeList1.addAll(beforePostI, temp2);
					routeNodeList2.addAll(nextPostI, temp1);
					ArrayList<OrderItem> carryItems = new ArrayList<OrderItem>();
					double cost1;
					if (nextVehicle.getDes() != null) {
						carryItems = (ArrayList<OrderItem>) nextVehicle.getCarryingItems();
					}
					if (!isFeasible(routeNodeList2, carryItems, nextVehicle.getBoardCapacity())) {
						cost1 = Double.MAX_VALUE;
					} else {
						cost1 = cost(routeNodeList1, beforeVehicle);
					}
					if (cost1 < minCost) {
						isImproved = true;
						minCostBlock1KeyStr = beforeKey;
						minCostBlock2KeyStr = nextKey;
						minCostBlock1 = new ArrayList<>(beforeBlock);
						minCostBlock2 = new ArrayList<>(nextBlock);
					}
					routeNodeList1.subList(beforePostI, beforePostI + block2Len).clear();
					routeNodeList2.subList(nextPostI, nextPostI + block1Len).clear();
					routeNodeList1.addAll(beforePostI, temp1);
					routeNodeList2.addAll(nextPostI, temp2);
				} else {
					if (beforePostJ < nextPostI || nextPostJ < beforePostI) {
						if (nextPostJ < beforePostI) {
							int temp = beforePostI;
							beforePostI = nextPostI;
							nextPostI = temp;
							temp = beforePostJ;
							beforePostJ = nextPostJ;
							nextPostJ = temp;
							temp = block1Len;
							block1Len = block2Len;
							block2Len = temp;
						}
						List<Node> temp1 = new ArrayList<Node>(
								routeNodeList1.subList(beforePostI, beforePostI + block1Len));
						List<Node> temp2 = new ArrayList<Node>(
								routeNodeList1.subList(nextPostI, nextPostI + block2Len));
						routeNodeList1.subList(nextPostI, nextPostI + block2Len).clear();
						routeNodeList1.subList(beforePostI, beforePostI + block1Len).clear();
						routeNodeList1.addAll(beforePostI, temp2);
						int realNextPostI = nextPostI + (block2Len - block1Len);
						routeNodeList1.addAll(realNextPostI, temp1);
						double cost1 = cost(routeNodeList1, beforeVehicle);
						if (cost1 < minCost) {
							isImproved = true;
							minCostBlock1KeyStr = beforeKey;
							minCostBlock2KeyStr = nextKey;
							minCostBlock1 = new ArrayList<>(beforeBlock);
							minCostBlock2 = new ArrayList<>(nextBlock);
						}
						routeNodeList1.subList(realNextPostI, realNextPostI + block1Len).clear();
						routeNodeList1.subList(beforePostI, beforePostI + block2Len).clear();
						routeNodeList1.addAll(beforePostI, temp1);
						routeNodeList1.addAll(nextPostI, temp2);
					}
				}
				idxJ++;
			}
			idxI++;
		}

		if (isImproved) {
			String beforeKey = minCostBlock1KeyStr;
			String beforeVid = beforeKey.split(",")[0];
			int beforePostI = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[0]);
			int beforePostJ = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[1]);
			ArrayList<Node> beforeBlock = minCostBlock1;
			int block1Len = beforeBlock.size();
			String nextKey = minCostBlock2KeyStr;
			String nextVid = nextKey.split(",")[0];
			int nextPostI = Integer.valueOf(nextKey.split(",")[1].split("\\+")[0]);
			int nextPostJ = Integer.valueOf(nextKey.split(",")[1].split("\\+")[1]);
			ArrayList<Node> nextDPG = minCostBlock2;
			int block2Len = nextDPG.size();
			ArrayList<Node> routeNodeList1 = vehicleId2PlannedRoute.get(beforeVid);
			if (!beforeVid.equals(nextVid)) {
				ArrayList<Node> routeNodeList2 = vehicleId2PlannedRoute.get(nextVid);
				List<Node> temp1 = new ArrayList<Node>(routeNodeList1.subList(beforePostI, beforePostJ + 1));
				List<Node> temp2 = new ArrayList<Node>(routeNodeList2.subList(nextPostI, nextPostJ + 1));
				routeNodeList1.subList(beforePostI, beforePostI + block1Len).clear();
				routeNodeList2.subList(nextPostI, nextPostI + block2Len).clear();
				routeNodeList1.addAll(beforePostI, temp2);
				routeNodeList2.addAll(nextPostI, temp1);
			} else {
				List<Node> temp1 = new ArrayList<Node>(routeNodeList1.subList(beforePostI, beforePostI + block1Len));
				List<Node> temp2 = new ArrayList<Node>(routeNodeList1.subList(nextPostI, nextPostI + block2Len));
				routeNodeList1.subList(nextPostI, nextPostI + block2Len).clear();
				routeNodeList1.subList(beforePostI, beforePostI + block1Len).clear();
				routeNodeList1.addAll(beforePostI, temp2);
				int realNextPostI = nextPostI + (block2Len - block1Len);
				routeNodeList1.addAll(realNextPostI, temp1);
			}
		}
		return isImproved;
	}

	private static boolean interCoupleExchange() {
		boolean isImproved = false;
		LinkedHashMap<String, ArrayList<Node>> cpVehicleId2PlannedRoute = new LinkedHashMap<>(vehicleId2PlannedRoute);
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> disOrderSuperNode = getUnongoingSuperNode();
		int lsNodePairNum = disOrderSuperNode.size();
		if (lsNodePairNum == 0) {
			return false;
		}
		String vehicleId = null;
		LinkedHashMap<String, ArrayList<Node>> pdgHashMap = new LinkedHashMap<String, ArrayList<Node>>();
		for (Entry<Integer, LinkedHashMap<String, Node>> pdgMap : disOrderSuperNode.entrySet()) {
			Integer idx = pdgMap.getKey();
			LinkedHashMap<String, Node> pdg = pdgMap.getValue();
			Node pickupNode, deliveryNode;
			ArrayList<Node> nodeList = new ArrayList<>();
			int posI = 0, posJ = 0;
			int dNum = pdg.size() / 2;
			Vehicle vehicle;
			int index = 0;
			if (null != pdg) {
				for (Entry<String, Node> nodeInfo : pdg.entrySet()) {
					String vAndPosStr = nodeInfo.getKey();
					Node node = nodeInfo.getValue();
					if (index % 2 == 0) {
						vehicleId = vAndPosStr.split(",")[0];
						posI = Integer.valueOf(vAndPosStr.split(",")[1]);
						pickupNode = node;
						nodeList.add(0, pickupNode);
						index++;
					} else {
						posJ = Integer.valueOf(vAndPosStr.split(",")[1]);
						deliveryNode = node;
						nodeList.add(deliveryNode);
						index++;
						posJ = posJ - dNum + 1;
					}
				}
				String k = vehicleId + "," + String.valueOf(posI) + "+" + String.valueOf(posJ);
				pdgHashMap.put(k, nodeList);
			}
		}
		if (pdgHashMap.size() < 2) {
			return false;
		}
		Vehicle vehicle = id2VehicleMap.get(vehicleId);
		ArrayList<Node> routeNodeList = vehicleId2PlannedRoute.get(vehicleId);
		double cost0 = cost(routeNodeList, vehicle);
		double originCost = cost0;
		double minCost = cost0;
		String minCostPDG1KeyStr = null, minCostPDG2KeyStr = null;
		ArrayList<Node> minCostPDG1 = null, minCostPDG2 = null;
		int idxI = 0, idxJ = 0;
		for (Entry<String, ArrayList<Node>> pdgMap1 : pdgHashMap.entrySet()) {
			String beforeKey = pdgMap1.getKey();
			String beforeVid = beforeKey.split(",")[0];
			int beforePostI = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[0]);
			int beforePostJ = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[1]);
			Vehicle beforeVehicle = id2VehicleMap.get(beforeVid);
			ArrayList<Node> beforeDPG = pdgMap1.getValue();
			int d1Num = beforeDPG.size() / 2;
			idxJ = 0;
			for (Entry<String, ArrayList<Node>> pdgMap2 : pdgHashMap.entrySet()) {
				if (idxI >= idxJ) {
					idxJ++;
					continue;
				}
				String nextKey = pdgMap2.getKey();
				String nextVid = nextKey.split(",")[0];
				int nextPostI = Integer.valueOf(nextKey.split(",")[1].split("\\+")[0]);
				int nextPostJ = Integer.valueOf(nextKey.split(",")[1].split("\\+")[1]);
				Vehicle nextVehicle = id2VehicleMap.get(nextVid);
				ArrayList<Node> nextDPG = pdgMap2.getValue();
				int d2Num = nextDPG.size() / 2;
				if (beforeVid.equals(nextVid)) {
					continue;
				}
				ArrayList<Node> routeNodeList1 = vehicleId2PlannedRoute.get(beforeVid);
				ArrayList<Node> routeNodeList2 = vehicleId2PlannedRoute.get(nextVid);
				List<Node> temp1 = new ArrayList<Node>(routeNodeList1.subList(beforePostI, beforePostI + d1Num));
				List<Node> temp11 = new ArrayList<Node>(routeNodeList1.subList(beforePostJ, beforePostJ + d1Num));
				List<Node> temp2 = new ArrayList<Node>(routeNodeList2.subList(nextPostI, nextPostI + d2Num));
				List<Node> temp22 = new ArrayList<Node>(routeNodeList2.subList(nextPostJ, nextPostJ + d2Num));
				routeNodeList1.subList(beforePostI, beforePostI + d1Num).clear();
				routeNodeList2.subList(nextPostI, nextPostI + d2Num).clear();
				routeNodeList1.addAll(beforePostI, temp2);
				routeNodeList2.addAll(nextPostI, temp1);
				int realBeforePostJ = beforePostJ + (d2Num - d1Num);
				int realNextPostJ = nextPostJ + (d1Num - d2Num);
				routeNodeList1.subList(realBeforePostJ, realBeforePostJ + d1Num).clear();
				if (routeNodeList2.size() < realNextPostJ + d2Num) {
					System.err.println(222);
				}
				routeNodeList2.subList(realNextPostJ, realNextPostJ + d2Num).clear();
				routeNodeList1.addAll(realBeforePostJ, temp22);
				routeNodeList2.addAll(realNextPostJ, temp11);
				ArrayList<OrderItem> carryItems = new ArrayList<OrderItem>();
				double cost1;
				if (nextVehicle.getDes() != null) {
					carryItems = (ArrayList<OrderItem>) nextVehicle.getCarryingItems();
				}
				if (!isFeasible(routeNodeList2, carryItems, nextVehicle.getBoardCapacity())) {
					cost1 = Double.MAX_VALUE;
				} else {
					cost1 = cost(routeNodeList1, beforeVehicle);
				}
				if (cost1 < minCost) {
					minCost = cost1;
					isImproved = true;
					minCostPDG1KeyStr = beforeKey;
					minCostPDG2KeyStr = nextKey;
					minCostPDG1 = new ArrayList<>(beforeDPG);
					minCostPDG2 = new ArrayList<>(nextDPG);
				}
				routeNodeList1.subList(realBeforePostJ, realBeforePostJ + d2Num).clear();
				routeNodeList2.subList(realNextPostJ, realNextPostJ + d1Num).clear();
				routeNodeList1.addAll(realBeforePostJ, temp11);
				routeNodeList2.addAll(realNextPostJ, temp22);
				routeNodeList1.subList(beforePostI, beforePostI + d2Num).clear();
				routeNodeList2.subList(nextPostI, nextPostI + d1Num).clear();
				routeNodeList1.addAll(beforePostI, temp1);
				routeNodeList2.addAll(nextPostI, temp2);
				idxJ++;
			}
			idxI++;
		}
		if (isImproved) {
			String beforeKey = minCostPDG1KeyStr;
			String beforeVid = beforeKey.split(",")[0];
			int beforePostI = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[0]);
			int beforePostJ = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[1]);
			ArrayList<Node> beforeDPG = minCostPDG1;
			int d1Num = beforeDPG.size() / 2;
			String nextKey = minCostPDG2KeyStr;
			String nextVid = nextKey.split(",")[0];
			int nextPostI = Integer.valueOf(nextKey.split(",")[1].split("\\+")[0]);
			int nextPostJ = Integer.valueOf(nextKey.split(",")[1].split("\\+")[1]);
			ArrayList<Node> nextDPG = minCostPDG2;
			int d2Num = nextDPG.size() / 2;
			ArrayList<Node> routeNodeList1 = vehicleId2PlannedRoute.get(beforeVid);
			ArrayList<Node> routeNodeList2 = vehicleId2PlannedRoute.get(nextVid);
			List<Node> temp1 = new ArrayList<Node>(routeNodeList1.subList(beforePostI, beforePostI + d1Num));
			List<Node> temp11 = new ArrayList<Node>(routeNodeList1.subList(beforePostJ, beforePostJ + d1Num));
			List<Node> temp2 = new ArrayList<Node>(routeNodeList2.subList(nextPostI, nextPostI + d2Num));
			List<Node> temp22 = new ArrayList<Node>(routeNodeList2.subList(nextPostJ, nextPostJ + d2Num));
			routeNodeList1.subList(beforePostI, beforePostI + d1Num).clear();
			routeNodeList2.subList(nextPostI, nextPostI + d2Num).clear();
			routeNodeList1.addAll(beforePostI, temp2);
			routeNodeList2.addAll(nextPostI, temp1);
			int realBeforePostJ = beforePostJ + (d2Num - d1Num);
			int realNextPostJ = nextPostJ + (d1Num - d2Num);
			routeNodeList1.subList(realBeforePostJ, realBeforePostJ + d1Num).clear();
			routeNodeList2.subList(realNextPostJ, realNextPostJ + d2Num).clear();
			routeNodeList1.addAll(realBeforePostJ, temp22);
			routeNodeList2.addAll(realNextPostJ, temp11);
		}
		return isImproved;
	}

	/**
	 * 一次可移动多个PDG进行提升CI-improve得到的性能
	 * 
	 * @return isImproved,如果有改进解就返回true,没改进则返回false
	 */
	private static boolean multiPDGroupRelocate() {
		boolean isImproved = false;
		LinkedHashMap<String, ArrayList<Node>> cpVehicleId2PlannedRoute = new LinkedHashMap<>(vehicleId2PlannedRoute);
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> disOrderSuperNode = getUnongoingSuperNode();
		int lsNodePairNum = disOrderSuperNode.size();
		LinkedHashMap<Integer, LinkedHashMap<String, ArrayList<Node>>> formalSuperNode = new LinkedHashMap<>();
		LinkedHashMap<Integer, LinkedHashMap<String, ArrayList<Node>>> newFormalSuperNode = new LinkedHashMap<>();
		Double[] newCostDelta = new Double[lsNodePairNum];
		Arrays.fill(newCostDelta, Double.MAX_VALUE);
		for (Entry<Integer, LinkedHashMap<String, Node>> pdgMap : disOrderSuperNode.entrySet()) {
			Integer idx = pdgMap.getKey();
			LinkedHashMap<String, Node> pdg = pdgMap.getValue();
			Node pickupNode, deliveryNode;
			ArrayList<Node> nodeList = new ArrayList<>();
			int posI = 0, posJ = 0;
			int dNum = pdg.size() / 2;
			String vehicleId = null;
			Vehicle vehicle;
			int index = 0;
			double currentCostDelta;
			if (null != pdg) {
				for (Entry<String, Node> nodeInfo : pdg.entrySet()) {
					String vAndPosStr = nodeInfo.getKey();
					Node node = nodeInfo.getValue();
					if (index % 2 == 0) {
						vehicleId = vAndPosStr.split(",")[0];
						posI = Integer.valueOf(vAndPosStr.split(",")[1]);
						pickupNode = node;
						nodeList.add(0, pickupNode);
						index++;
					} else {
						posJ = Integer.valueOf(vAndPosStr.split(",")[1]);
						deliveryNode = node;
						nodeList.add(deliveryNode);
						index++;
						posJ = posJ - dNum + 1;
					}
				}
				LinkedHashMap<String, ArrayList<Node>> pdgHashMap = new LinkedHashMap<String, ArrayList<Node>>();
				String k = vehicleId + "," + String.valueOf(posI) + "+" + String.valueOf(posJ);
				pdgHashMap.put(k, nodeList);
				formalSuperNode.put(idx, pdgHashMap);
				newFormalSuperNode.put(idx, pdgHashMap);
			}
			ArrayList<Node> routeNodeList = new ArrayList<>(cpVehicleId2PlannedRoute.get(vehicleId));
			vehicle = id2VehicleMap.get(vehicleId);
			double costAfterInsert = singleVehicleCost(routeNodeList, vehicle);
			routeNodeList.removeAll(routeNodeList.subList(posI, posI + dNum));
			routeNodeList.removeAll(routeNodeList.subList(posJ - dNum, posJ));
			cpVehicleId2PlannedRoute.put(vehicleId, routeNodeList);
			double costBeforeInsert = singleVehicleCost(routeNodeList, vehicle);
			currentCostDelta = costAfterInsert - costBeforeInsert;
			dispatchOrder2Best(nodeList, cpVehicleId2PlannedRoute);
			if (minCostDelta < currentCostDelta) {
				newCostDelta[idx] = minCostDelta;
				LinkedHashMap<String, ArrayList<Node>> pdgHashMap = new LinkedHashMap<String, ArrayList<Node>>();
				String k = bestInsertVehicleId + "," + String.valueOf(bestInsertPosI) + "+"
						+ String.valueOf(bestInsertPosJ);
				pdgHashMap.put(k, nodeList);
				newFormalSuperNode.put(idx, pdgHashMap);
			}
			routeNodeList.addAll(posI, nodeList.subList(0, nodeList.size() / 2));
			routeNodeList.addAll(posJ, nodeList.subList(nodeList.size() / 2, nodeList.size()));
		}
		Double[] costDeltaTemp = new Double[lsNodePairNum];
		System.arraycopy(newCostDelta, 0, costDeltaTemp, 0, lsNodePairNum);
		int[] sortIndex = sortToIndex(costDeltaTemp);
		boolean[] mask = new boolean[id2VehicleMap.size()];
		double orginCost = -1.0;
		double finalCost = -1.0;
		for (int i = 0; i < lsNodePairNum; i++) {
			if (newCostDelta[i] != Double.MAX_VALUE) {
				LinkedHashMap<String, ArrayList<Node>> beforeSuperNodeMap = formalSuperNode.get(sortIndex[i]);
				LinkedHashMap<String, ArrayList<Node>> newSuperNodeMap = newFormalSuperNode.get(sortIndex[i]);
				String beforeKey = beforeSuperNodeMap.entrySet().iterator().next().getKey();
				String beforeVid = beforeKey.split(",")[0];
				int beforePostI = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[0]);
				int beforePostJ = Integer.valueOf(beforeKey.split(",")[1].split("\\+")[1]);
				ArrayList<Node> beforeDPG = beforeSuperNodeMap.entrySet().iterator().next().getValue();
				int dNum = beforeDPG.size() / 2;
				int beforeVehicleIdx = Integer.valueOf(beforeVid.split("_")[1]) - 1;
				String newKey = newSuperNodeMap.entrySet().iterator().next().getKey();
				String newVid = newKey.split(",")[0];
				int newPostI = Integer.valueOf(newKey.split(",")[1].split("\\+")[0]);
				int newPostJ = Integer.valueOf(newKey.split(",")[1].split("\\+")[1]);
				ArrayList<Node> newDPG = newSuperNodeMap.entrySet().iterator().next().getValue();
				int newVehicleIdx = Integer.valueOf(newVid.split("_")[1]) - 1;
				if (!mask[beforeVehicleIdx] && !mask[newVehicleIdx]) {
					ArrayList<Node> beforeRouteNodeList = new ArrayList<>(vehicleId2PlannedRoute.get(beforeVid));
					Vehicle beforeVehicle = id2VehicleMap.get(beforeVid);
					double cost0 = cost(beforeRouteNodeList, beforeVehicle);
					if (orginCost < 0)
						orginCost = cost0;
					beforeRouteNodeList.removeAll(beforeRouteNodeList.subList(beforePostI, beforePostI + dNum));
					beforeRouteNodeList.removeAll(beforeRouteNodeList.subList(beforePostJ - dNum, beforePostJ));
					vehicleId2PlannedRoute.put(beforeVid, beforeRouteNodeList);
					ArrayList<Node> newRouteNodeList;
					if (vehicleId2PlannedRoute.get(newVid) != null) {
						newRouteNodeList = new ArrayList<>(vehicleId2PlannedRoute.get(newVid));
					} else {
						newRouteNodeList = new ArrayList<Node>();
					}
					Vehicle newVehicle = id2VehicleMap.get(newVid);
					newRouteNodeList.addAll(newPostI, newDPG.subList(0, newDPG.size() / 2));
					newRouteNodeList.addAll(newPostJ, newDPG.subList(newDPG.size() / 2, newDPG.size()));
					double cost1 = cost(newRouteNodeList, newVehicle);
					if (cost0 <= cost1) {
						beforeRouteNodeList.addAll(beforePostI, beforeDPG.subList(0, beforeDPG.size() / 2));
						beforeRouteNodeList.addAll(beforePostJ,
								beforeDPG.subList(beforeDPG.size() / 2, beforeDPG.size()));
						vehicleId2PlannedRoute.put(beforeVid, beforeRouteNodeList);
					} else {
						finalCost = cost1;

						mask[beforeVehicleIdx] = true;
						mask[newVehicleIdx] = true;
						isImproved = true;
						vehicleId2PlannedRoute.put(newVid, newRouteNodeList);
					}
				}
			}

		}
		return isImproved;
	}

	/**
	 * Return index after sorting`
	 * 
	 * @param v
	 * @param <T>
	 * @return
	 */
	static <T> int[] sortToIndex(T[] v) {
		Map<T, Queue<Integer>> indexMap = new HashMap<>();
		for (int i = 0; i < v.length; i++) {
			Queue<Integer> indexes = indexMap.get(v[i]);
			if (indexes == null)
				indexes = new ArrayDeque<>();
			indexes.add(i);
			indexMap.put(v[i], indexes);
		}
		List<T> vSorted = Arrays.stream(v).sorted().collect(Collectors.toList());
		int[] indexes = new int[vSorted.size()];
		for (int i = 0; i < vSorted.size(); i++) {
			Queue<Integer> itemIndexes = indexMap.get(vSorted.get(i));
			Integer index = itemIndexes.poll();
			if (index == null)
				throw new RuntimeException("itemIndexes is empty");
			indexes[i] = index;
		}
		return indexes;
	}

	/**
	 * 找最佳插入的PD团的位置
	 * 
	 * @param nodeList
	 * @param cpVehicleId2PlannedRoute
	 */
	private static void dispatchOrder2Best(ArrayList<Node> nodeList,
			LinkedHashMap<String, ArrayList<Node>> cpVehicleId2PlannedRoute) {

		List<Node> pickupNodeList = (List<Node>) nodeList.subList(0, nodeList.size() / 2);
		List<Node> deliveryNodeList = (List<Node>) nodeList.subList(nodeList.size() / 2, nodeList.size());
		ArrayList<Node> routeNodeList;
		minCostDelta = Double.MAX_VALUE;
		int blockLen = pickupNodeList.size();
		int index = 0;
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = "V_" + String.valueOf(++index);
			Vehicle vehicle = id2VehicleItem.getValue();
			routeNodeList = cpVehicleId2PlannedRoute.get(vehicleId);
			double costValue0 = singleVehicleCost(routeNodeList, vehicle);
			int nodeListSize = 0;
			HashMap<String, Integer> coupleEndIdxMap = null;
			if (routeNodeList != null) {
				nodeListSize = routeNodeList.size();
				coupleEndIdxMap = getCoupleEndIdxMap(routeNodeList);
			}
			int insertPos = 0;

			if (vehicle.getDes() != null) {
				insertPos = 1;
			}
			for (int i = insertPos; i <= nodeListSize; i++) {
				ArrayList<Node> tempRouteNodeList;
				if (null != routeNodeList) {
					tempRouteNodeList = new ArrayList<Node>(routeNodeList);
				} else {
					tempRouteNodeList = new ArrayList<Node>();
				}
				tempRouteNodeList.addAll(i, pickupNodeList);
				for (int j = blockLen + i; j <= nodeListSize + blockLen; j++) {
					if (j == blockLen + i) {
						tempRouteNodeList.addAll(j, deliveryNodeList);
					} else {
						if (tempRouteNodeList.get(j - 1).getPickupItemList() != null
								&& tempRouteNodeList.get(j - 1).getPickupItemList().size() > 0) {
							String orderItemId = tempRouteNodeList.get(j - 1).getPickupItemList().get(0).getId();
							j = blockLen + coupleEndIdxMap.get(orderItemId) + 1;
							tempRouteNodeList.addAll(j, deliveryNodeList);
						} else if (tempRouteNodeList.get(j - 1).getDeliveryItemList() != null
								&& !tempRouteNodeList.get(j - 1).getDeliveryItemList().isEmpty()) {
							boolean isTerminal = true;
							for (int k = j - 2; k >= 0; k--) {
								if (tempRouteNodeList.get(k).getPickupItemList() != null
										&& !tempRouteNodeList.get(k).getPickupItemList().isEmpty()) {
									int len = tempRouteNodeList.get(j - 1).getDeliveryItemList().size();
									if (tempRouteNodeList.get(j - 1).getDeliveryItemList().get(len - 1).getId()
											.equals(tempRouteNodeList.get(k).getPickupItemList().get(0).getId())) {
										if (k < i) {
											isTerminal = true;
											break;
										} else if (k > i) {
											isTerminal = false;
											break;
										}
									}
								}
							}
							if (isTerminal) {
								break;
							}
							tempRouteNodeList.addAll(j, deliveryNodeList);
						}
					}
					double costValue = singleVehicleCost(tempRouteNodeList, vehicle);
					tempRouteNodeList.removeAll(tempRouteNodeList.subList(j, j + blockLen));
					if (costValue - costValue0 < minCostDelta) {
						minCostDelta = costValue - costValue0;
						bestInsertPosI = i;
						bestInsertPosJ = j;
						bestInsertVehicleId = vehicleId;
					}
				}
			}
		}
	}

	/**
	 * 计算单车辆的cost值
	 * 
	 * @param routeNodeList
	 * @param vehicle
	 * @return
	 */
	private static double singleVehicleCostX(ArrayList<Node> tempRouteNodeList, Vehicle vehicle) {
		String curFactoryId = vehicle.getCurFactoryId();
		int untilTime = 0;
		double drivingDistance = 0;
		double overTimeSum = 0;
		int orderNum = 0;
		double objF;
		double capacity = vehicle.getBoardCapacity();
		double distance = 0;
		int time = 0;
		if (tempRouteNodeList == null || tempRouteNodeList.size() == 0) {
			return 0;
		}
		ArrayList<OrderItem> carryItems = new ArrayList<OrderItem>();
		if (vehicle.getDes() != null) {
			carryItems = (ArrayList<OrderItem>) vehicle.getCarryingItems();
		}
		if (!isFeasible(tempRouteNodeList, carryItems, capacity)) {
			return Double.MAX_VALUE;
		}

		if (vehicle.getDes() == null) {
			if (vehicle.getCurFactoryId() == "") {
				System.err.println("cur factory have no value");
			}
			if (tempRouteNodeList.size() == 0) {
				System.err.println("tempRouteNodeList have no length");
			}

			if (vehicle.getCurFactoryId().equals(tempRouteNodeList.get(0).getId())) {
				untilTime = vehicle.getGpsUpdateTime();
			} else {
				String disAndTimeStr = id2RouteMap
						.get(vehicle.getCurFactoryId() + "+" + tempRouteNodeList.get(0).getId());
				String[] disAndTime = disAndTimeStr.split("\\+");
				distance = Double.parseDouble(disAndTime[0]);
				time = Integer.parseInt(disAndTime[1]);
				untilTime = vehicle.getGpsUpdateTime() + time;
				drivingDistance = drivingDistance + distance;
			}
		} else {
			if (curFactoryId != null && curFactoryId.length() > 0) {
				if (!curFactoryId.equals(tempRouteNodeList.get(0).getId())) {
					untilTime = vehicle.getLeaveTimeAtCurrentFactory();
					String disAndTimeStr = id2RouteMap.get(curFactoryId + "+" + tempRouteNodeList.get(0).getId());
					String[] disAndTime = disAndTimeStr.split("\\+");
					distance = Double.parseDouble(disAndTime[0]);
					time = Integer.parseInt(disAndTime[1]);
					drivingDistance = drivingDistance + distance;
					untilTime += time;
				} else {
					untilTime = vehicle.getLeaveTimeAtCurrentFactory();
				}
			} else {
				untilTime = vehicle.getDes().getArriveTime();
			}
		}

		for (int i = 0; i < tempRouteNodeList.size();) {
			Node curNode = tempRouteNodeList.get(i);
			if (curNode.getDeliveryItemList() != null && curNode.getDeliveryItemList().size() > 0) {
				String beforeOrderId = "", nextOrderId = "";
				for (OrderItem orderItem : curNode.getDeliveryItemList()) {
					nextOrderId = orderItem.getOrderId();
					if (!beforeOrderId.equals(nextOrderId)) {
						int commitCompleteTime = orderItem.getCommittedCompletionTime();
						overTimeSum += Math.max(0, untilTime - commitCompleteTime);
					}
					beforeOrderId = nextOrderId;
				}
			}
			int serviceTime = curNode.getServiceTime();
			String curFatoryId = curNode.getId();
			i++;
			while (i < tempRouteNodeList.size()
					&& curFatoryId.equals(tempRouteNodeList.get(i).getId())) {
				if (tempRouteNodeList.get(i).getDeliveryItemList() != null
						&& tempRouteNodeList.get(i).getDeliveryItemList().size() > 0) {
					String beforeOrderId = "", nextOrderId = "";
					for (OrderItem orderItem : tempRouteNodeList.get(i).getDeliveryItemList()) {
						nextOrderId = orderItem.getOrderId();
						if (!beforeOrderId.equals(nextOrderId)) {
							int commitCompleteTime = orderItem.getCommittedCompletionTime();
							overTimeSum += Math.max(0, untilTime - commitCompleteTime);
						}
						beforeOrderId = nextOrderId;
					}
				}
				serviceTime += tempRouteNodeList.get(i).getServiceTime();
				i++;
			}

			if (i >= tempRouteNodeList.size()) {
				break;
			} else {
				String disAndTimeStr = id2RouteMap.get(curFatoryId + "+" + tempRouteNodeList.get(i).getId());
				String[] disAndTime = disAndTimeStr.split("\\+");
				distance = Double.parseDouble(disAndTime[0]);
				time = Integer.parseInt(disAndTime[1]);
				untilTime = untilTime + APPROACHING_DOCK_TIME + serviceTime + time;
				drivingDistance += distance;
			}
		}
		objF = Delta * overTimeSum + drivingDistance;
		if (objF < 0) {
			System.err.println("the objective function less than 0.");
		}
		return objF;
	}

	/**
	 * 计算单车辆的cost值
	 * 
	 * @param routeNodeList
	 * @param vehicle
	 * @return
	 */
	private static double singleVehicleCost(ArrayList<Node> tempRouteNodeList, Vehicle vehicle) {
		String curFactoryId = vehicle.getCurFactoryId();
		int utilTime = 0;
		double drivingDistance = 0;
		double overTimeSum = 0;
		int orderNum = 0;
		double objF;
		double capacity = vehicle.getBoardCapacity();

		if (tempRouteNodeList == null || tempRouteNodeList.size() == 0) {
			return 0;
		}

		ArrayList<OrderItem> carryItems = new ArrayList<OrderItem>();
		if (vehicle.getDes() != null) {
			carryItems = (ArrayList<OrderItem>) vehicle.getCarryingItems();
		}
		if (!isFeasible(tempRouteNodeList, carryItems, capacity)) {
			return Double.MAX_VALUE;
		}

		if (curFactoryId != null && curFactoryId.length() > 0) {
			utilTime = vehicle.getLeaveTimeAtCurrentFactory();
			for (int i = 0; i < tempRouteNodeList.size(); i++) {
				Node nextNode = tempRouteNodeList.get(i);
				String toFactoryId = nextNode.getId();
				double distance = 0;
				int time = 0;
				if (!curFactoryId.equals(toFactoryId)) {
					String disAndTimeStr = id2RouteMap.get(curFactoryId + "+" + toFactoryId);
					String[] disAndTime = disAndTimeStr.split("\\+");
					distance = Double.parseDouble(disAndTime[0]);
					time = Integer.parseInt(disAndTime[1]);
					utilTime += APPROACHING_DOCK_TIME;
				}
				drivingDistance += distance;
				utilTime += time;
				if (nextNode.getDeliveryItemList() != null && nextNode.getDeliveryItemList().size() > 0) {
					String beforeOrderId = "", nextOrderId = "";
					for (OrderItem orderItem : nextNode.getDeliveryItemList()) {
						nextOrderId = orderItem.getOrderId();
						if (!beforeOrderId.equals(nextOrderId)) {
							int commitCompleteTime = orderItem.getCommittedCompletionTime();
							overTimeSum += Math.max(0, utilTime - commitCompleteTime);
						}
						beforeOrderId = nextOrderId;
					}
				}
				utilTime += nextNode.getServiceTime();
				curFactoryId = toFactoryId;
			}
		} else {
			utilTime = vehicle.getDes().getArriveTime();
			curFactoryId = tempRouteNodeList.get(0).getId();
			Node curNode = tempRouteNodeList.get(0);
			if (curNode.getDeliveryItemList() != null && curNode.getDeliveryItemList().size() > 0) {
				String beforeOrderId = "", nextOrderId = "";
				for (OrderItem orderItem : curNode.getDeliveryItemList()) {
					nextOrderId = orderItem.getOrderId();
					if (!beforeOrderId.equals(nextOrderId)) {
						int commitCompleteTime = orderItem.getCommittedCompletionTime();
						overTimeSum += Math.max(0, utilTime - commitCompleteTime);
					}
					beforeOrderId = nextOrderId;
				}
			}
			for (int i = 1; i < tempRouteNodeList.size(); i++) {
				Node nextNode = tempRouteNodeList.get(i);
				String toFactoryId = nextNode.getId();
				double distance = 0;
				int time = 0;
				if (!curFactoryId.equals(toFactoryId)) {
					String disAndTimeStr = id2RouteMap.get(curFactoryId + "+" + toFactoryId);
					String[] disAndTime = disAndTimeStr.split("\\+");
					distance = Double.parseDouble(disAndTime[0]);
					time = Integer.parseInt(disAndTime[1]);
					utilTime += APPROACHING_DOCK_TIME;
				}
				drivingDistance += distance;
				utilTime += time;
				if (nextNode.getDeliveryItemList() != null && nextNode.getDeliveryItemList().size() > 0) {
					String beforeOrderId = "", nextOrderId = "";
					for (OrderItem orderItem : nextNode.getDeliveryItemList()) {
						nextOrderId = orderItem.getOrderId();
						if (!beforeOrderId.equals(nextOrderId)) {
							int commitCompleteTime = orderItem.getCommittedCompletionTime();
							overTimeSum += Math.max(0, utilTime - commitCompleteTime);
						}
						beforeOrderId = nextOrderId;
					}
				}
				utilTime += nextNode.getServiceTime();
				curFactoryId = toFactoryId;
			}
		}
		objF = Delta1 * overTimeSum + drivingDistance;
		return objF;
	}

	/**
	 * 更新dock
	 */
	private static void updateDockTable() {
		int vehicleNum = id2VehicleMap.size(), n = vehicleNum;
		int[] curNode = new int[vehicleNum];
		int t0 = id2VehicleMap.get("V_1").getGpsUpdateTime();
		for (Entry<String, Factory> id2Factory : id2FactoryMap.entrySet()) {
			String factoryId = id2Factory.getKey();
			ArrayList<Integer> time = new ArrayList<Integer>();
			time.add(t0);
			time.add(Integer.MAX_VALUE);
			ArrayList<Integer> dock = new ArrayList<Integer>();
			dock.add(1);
			ArrayList<ArrayList<Integer>> DockTable = new ArrayList<>();
			DockTable.add(time);
			DockTable.add(dock);
			id2DockTableMap.put(factoryId, DockTable);
		}
		boolean flag = false;
		int[] leaveBeforeNodeTime = new int[vehicleNum];
		while (n > 0) {
			int index = 0;
			int t = t0;
			n = vehicleNum;
			flag = false;
			int minT = Integer.MAX_VALUE, minT2VehicleIndex = 0;
			String minTVehicleId = null;
			ArrayList<Node> minTNodeList = new ArrayList<>();
			for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
				String vehicleId = id2NodesMap.getKey();
				Vehicle vehicle = id2VehicleMap.get(vehicleId);
				ArrayList<Node> id2NodeList = id2NodesMap.getValue();
				int nodeSize = id2NodeList == null ? 0 : id2NodeList.size();
				if (curNode[index] < nodeSize) {
					flag = true;
					t = get_t(id2NodeList, vehicle, curNode[index], leaveBeforeNodeTime[index]);
					if (t < minT) {
						minT = t;
						minT2VehicleIndex = index;
						minTNodeList = id2NodeList;
						minTVehicleId = "V_" + String.valueOf(minT2VehicleIndex + 1);
					}
				} else {
					n--;
				}
				index++;
			}
			if (flag) {
				String curFatoryId = minTNodeList.get(curNode[minT2VehicleIndex]).getId();
				int serviceTime = minTNodeList.get(curNode[minT2VehicleIndex]).getServiceTime();
				int nodesLen = minTNodeList.size();
				curNode[minT2VehicleIndex]++;
				while (curNode[minT2VehicleIndex] < nodesLen
						&& curFatoryId.equals(minTNodeList.get(curNode[minT2VehicleIndex]).getId())) {
					serviceTime += minTNodeList.get(curNode[minT2VehicleIndex]).getServiceTime();
					curNode[minT2VehicleIndex]++;
				}
				int tTrue = t, tStartIndex = 1, tEndIndex = 1;
				ArrayList<ArrayList<Integer>> dockTable = id2DockTableMap.get(curFatoryId);
				ArrayList<Integer> timeList = dockTable.get(0);
				ArrayList<Integer> dockList = dockTable.get(1);
				for (int i = 1; i < timeList.size(); i++) {
					if (t < timeList.get(i)) {
						tStartIndex = i - 1;
						tEndIndex = i;
						if (dockList.get(i - 1) > 0) {
							tTrue = t;
						} else {
							for (int j = i + 1; j < timeList.size(); j++) {
								if (dockList.get(j - 1) == 1) {
									tTrue = timeList.get(j - 1);
								}
							}
						}
						break;
					}
				}
				int leaveTime;
				if (curNode[minT2VehicleIndex] == 1 && id2VehicleMap.get(minTVehicleId).getCurFactoryId() != null
						&& id2VehicleMap.get(minTVehicleId).getCurFactoryId().length() > 0
						&& id2VehicleMap.get(minTVehicleId).getDes() != null) {
					leaveTime = id2VehicleMap.get(minTVehicleId).getLeaveTimeAtCurrentFactory();
				} else {
					leaveTime = tTrue + serviceTime + 1800;
				}

				leaveBeforeNodeTime[minT2VehicleIndex] = leaveTime;
				int insertSecondDockValue = dockList.get(tEndIndex - 1);
				timeList.add(tStartIndex + 1, t);
				dockList.add(tStartIndex + 1, dockList.get(tStartIndex) - 1);
				int leaveStart, leaveEnd;
				int len = timeList.size();
				for (int i = tStartIndex + 2; i < len; i++) {
					if (leaveTime > timeList.get(i)) {
						int orignValue = dockList.get(i);
						dockList.set(i, orignValue - 1);
					}
					timeList.add(i, leaveTime);
					dockList.add(i, insertSecondDockValue);
				}
				dockTable.clear();
				dockTable.add(timeList);
				dockTable.add(dockList);
				id2DockTableMap.put(curFatoryId, dockTable);
			}
		}
	}

	/**
	 * 获取车辆到达第一个节点的时间t
	 * 
	 * @param id2NodeList 车辆节点列表
	 * @param vehicle
	 * @param curPos      当前节点位置
	 * @return
	 */
	private static int get_t(ArrayList<Node> id2NodeList, Vehicle vehicle, int curPos, int leaveBeforeNodeTime) {
		int t;
		String curFactoryId;
		if (curPos == 0) {
			curFactoryId = vehicle.getCurFactoryId();
			t = vehicle.getGpsUpdateTime();
			if (curFactoryId != null && curFactoryId.length() > 0 && vehicle.getDes() == null) {
				String toFactoryId = id2NodeList.get(curPos).getId();
				int time = 0;
				if (!curFactoryId.equals(toFactoryId)) {
					String disAndTimeStr = id2RouteMap.get(curFactoryId + "+" + toFactoryId);
					String[] disAndTime = disAndTimeStr.split("\\+");
					time = Integer.parseInt(disAndTime[1]);
				}
				t = vehicle.getLeaveTimeAtCurrentFactory() + time;
			} else if (curFactoryId.length() == 0 && vehicle.getDes() != null) {
				t = vehicle.getDes().getArriveTime();
			} else if (curPos == 0 && vehicle.getGpsUpdateTime() < vehicle.getLeaveTimeAtCurrentFactory()) {
				t = vehicle.getGpsUpdateTime();
			}
		} else {
			t = leaveBeforeNodeTime;
			curFactoryId = id2NodeList.get(curPos - 1).getId();
			String toFactoryId = id2NodeList.get(curPos).getId();
			int time = 0;
			if (!curFactoryId.equals(toFactoryId)) {
				String disAndTimeStr = id2RouteMap.get(curFactoryId + "+" + toFactoryId);
				String[] disAndTime = disAndTimeStr.split("\\+");
				time = Integer.parseInt(disAndTime[1]);
			}
			t += time;
		}

		return t;
	}

	/**
	 * 获取车辆实际开始占用垛口的时间节点
	 * 
	 * @return
	 */
	private static int get_tTrue(String factoryId, int t) {
		int tTrue = t;
		ArrayList<ArrayList<Integer>> dockTable = id2DockTableMap.get(factoryId);
		ArrayList<Integer> timeList = dockTable.get(0);
		ArrayList<Integer> dockList = dockTable.get(1);
		for (int i = 1; i < timeList.size(); i++) {
			if (t < timeList.get(i)) {
				if (dockList.get(i - 1) > 0) {
					tTrue = t;
				} else {
					for (int j = i + 1; j < timeList.size(); j++) {
						if (dockList.get(j - 1) == 1) {
							tTrue = timeList.get(j - 1);
						}
					}
				}
			}
		}
		return tTrue;
	}

	/**
	 * 210702 接入improve-CI后的LS
	 * 对CI得到的解进行优化，若未分配的订单数少于Nmax,将这些订单组成随机数列的顺序以最小代价进行插入到所有车中，否则，只选择随机数列的前Nmax个
	 */
	private static boolean randomLocalSearch() {
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> unongoingNodeMap = getUnongoingNodeMap();
		int lsNodePairNum = unongoingNodeMap.size();
		if (lsNodePairNum < 1)
			return false;
		boolean isSucc = false;
		int n = 0;
		int succn = 0;
		double currentCostDelta;
		long endtime = System.nanoTime();
		usedTime = (endtime - begintime) / (1e9);
		while (!isSucc && n < Nmax && usedTime < 9 * 60 && lsNodePairNum > 0) {
			Random rand = new Random();
			int select = rand.nextInt(lsNodePairNum);
			LinkedHashMap<String, Node> PAndDNode = unongoingNodeMap.get(select);
			Node pickupNode, deliveryNode;
			ArrayList<Node> nodeList = new ArrayList<>();
			int posI = 0, posJ = 0;
			String vehicleId = null;
			Vehicle vehicle;
			int index = 0;
			if (null != PAndDNode) {
				for (Entry<String, Node> nodeInfo : PAndDNode.entrySet()) {
					String vAndPosStr = nodeInfo.getKey();
					Node node = nodeInfo.getValue();
					if (0 == index) {
						vehicleId = vAndPosStr.split(",")[0];
						posI = Integer.valueOf(vAndPosStr.split(",")[1]);
						pickupNode = node;
						nodeList.add(pickupNode);
						index++;
					} else {
						posJ = Integer.valueOf(vAndPosStr.split(",")[1]);
						deliveryNode = node;
						nodeList.add(deliveryNode);
					}
				}
				ArrayList<Node> routeNodeList = vehicleId2PlannedRoute.get(vehicleId);
				vehicle = id2VehicleMap.get(vehicleId);
				double costAfterInsert = cost(routeNodeList, vehicle);
				routeNodeList.remove(posI);
				routeNodeList.remove(posJ - 1);
				vehicleId2PlannedRoute.put(vehicleId, routeNodeList);
				currentCostDelta = costAfterInsert;
				dispatchNewOrdersWithImproveCI(nodeList);
				if (minCostDelta < costAfterInsert) {
					n = 0;
					succn++;
					ArrayList<Node> insertRouteNodeList = vehicleId2PlannedRoute.get(bestInsertVehicleId);
					if (isExhaustive) {
						insertRouteNodeList = new ArrayList<>(bestNodeList);
					} else {
						if (null == insertRouteNodeList) {
							insertRouteNodeList = new ArrayList<Node>();
						}
						Node newOrderPickupNode = nodeList.get(0);
						Node newOrderDeliveryNode = nodeList.get(1);
						insertRouteNodeList.add(bestInsertPosI, newOrderPickupNode);
						insertRouteNodeList.add(bestInsertPosJ, newOrderDeliveryNode);
					}
					vehicleId2PlannedRoute.put(bestInsertVehicleId, insertRouteNodeList);
					isSucc = true;
				} else {
					n++;
					ArrayList<Node> initialRouteNodeList = vehicleId2PlannedRoute.get(vehicleId);
					if (null == initialRouteNodeList) {
						initialRouteNodeList = new ArrayList<Node>();
					}
					Node newOrderPickupNode = nodeList.get(0);
					Node newOrderDeliveryNode = nodeList.get(1);
					initialRouteNodeList.add(posI, newOrderPickupNode);
					initialRouteNodeList.add(posJ, newOrderDeliveryNode);
					vehicleId2PlannedRoute.put(vehicleId, initialRouteNodeList);
				}
			}
			endtime = System.nanoTime();
			usedTime = (endtime - begintime) / (1e9);
		}
		return isSucc;
	}

	/**
	 * LS: 移动的是PD对，且使用relocate-couple的方式进行移动
	 */
	private static boolean randomLocalSearchWithRelocateBlock() {
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> unongoingSuperNode = getUnongoingSuperNode();
		int lsNodePairNum = unongoingSuperNode.size();

		if (lsNodePairNum < 1) {
			System.err.println("nPDG:" + lsNodePairNum + "<1");
			return false;
		}
		int n = 0;
		long endtime = System.nanoTime();
		usedTime = (endtime - begintime) / (1e9);
		double cost0 = cost(vehicleId2PlannedRoute.get("V_1"), id2VehicleMap.get("V_1"));
		double originCost = cost0;
		int select = -1;
		String selectVehicleId = "";
		int selectPosI = -1;
		int selectPosJ = -1;
		while (n < lsNodePairNum && usedTime < 9 * 60) {
			LinkedHashMap<String, Node> PAndDNode = unongoingSuperNode.get(n);
			Node pickupNode, deliveryNode;
			ArrayList<Node> nodeList = new ArrayList<>();
			int posI = 0, posJ = 0;
			int dNum = unongoingSuperNode.get(n).size() / 2;
			String vehicleId = null;
			int index = 0;
			if (null != PAndDNode) {
				for (Entry<String, Node> nodeInfo : PAndDNode.entrySet()) {
					String vAndPosStr = nodeInfo.getKey();
					Node node = nodeInfo.getValue();
					if (index % 2 == 0) {
						vehicleId = vAndPosStr.split(",")[0];
						posI = Integer.valueOf(vAndPosStr.split(",")[1]);
						pickupNode = node;
						nodeList.add(0, pickupNode);
						index++;
					} else {
						posJ = Integer.valueOf(vAndPosStr.split(",")[1]);
						deliveryNode = node;
						nodeList.add(deliveryNode);
						index++;
						posJ = posJ - dNum + 1;
					}
				}
			}
			ArrayList<Node> routeNodeList = new ArrayList<>(vehicleId2PlannedRoute.get(vehicleId));
			Vehicle vehicle = id2VehicleMap.get(vehicleId);
			routeNodeList.removeAll(routeNodeList.subList(posI, posI + dNum));
			routeNodeList.removeAll(routeNodeList.subList(posJ - dNum, posJ));
			vehicleId2PlannedRoute.put(vehicleId, routeNodeList);
			rellocateBlock(nodeList);
			if (minCostDelta < cost0) {
				cost0 = minCostDelta;
				select = n;
				selectVehicleId = bestInsertVehicleId;
				selectPosI = bestInsertPosI;
				selectPosJ = bestInsertPosJ;
			} 
			ArrayList<Node> initialRouteNodeList = vehicleId2PlannedRoute.get(vehicleId);
			if (null == initialRouteNodeList) {
				initialRouteNodeList = new ArrayList<Node>();
			}
			initialRouteNodeList.addAll(posI, nodeList.subList(0, nodeList.size() / 2));
			initialRouteNodeList.addAll(posJ, nodeList.subList(nodeList.size() / 2, nodeList.size()));
			vehicleId2PlannedRoute.put(vehicleId, initialRouteNodeList);
			n = n + 1;
			endtime = System.nanoTime();
			usedTime = (endtime - begintime) / (1e9);
		}
		if (select >= 0) {
			System.err.println(
					"PDG::originCost:" + originCost + ";newCost:" + cost0 + ";improved cost:" + (originCost - cost0));
			LinkedHashMap<String, Node> PAndDNode = unongoingSuperNode.get(select);
			Node pickupNode, deliveryNode;
			ArrayList<Node> nodeList = new ArrayList<>();
			int posI = 0, posJ = 0;
			int dNum = unongoingSuperNode.get(select).size() / 2;
			String vehicleId = null;
			Vehicle vehicle;
			int index = 0;
			if (null != PAndDNode) {
				for (Entry<String, Node> nodeInfo : PAndDNode.entrySet()) {
					String vAndPosStr = nodeInfo.getKey();
					Node node = nodeInfo.getValue();
					if (index % 2 == 0) {
						vehicleId = vAndPosStr.split(",")[0];
						posI = Integer.valueOf(vAndPosStr.split(",")[1]);
						pickupNode = node;
						nodeList.add(0, pickupNode);
						index++;
					} else {
						posJ = Integer.valueOf(vAndPosStr.split(",")[1]);
						deliveryNode = node;
						nodeList.add(deliveryNode);
						index++;
						posJ = posJ - dNum + 1;
					}
				}
			}
			ArrayList<Node> routeNodeList = new ArrayList<>(vehicleId2PlannedRoute.get(vehicleId));
			vehicle = id2VehicleMap.get(vehicleId);

			routeNodeList.removeAll(routeNodeList.subList(posI, posI + dNum));
			routeNodeList.removeAll(routeNodeList.subList(posJ - dNum, posJ));
			vehicleId2PlannedRoute.put(vehicleId, routeNodeList);
			ArrayList<Node> insertRouteNodeList = vehicleId2PlannedRoute.get(selectVehicleId);
			if (null == insertRouteNodeList) {
				insertRouteNodeList = new ArrayList<Node>();
			}
			insertRouteNodeList.addAll(selectPosI, nodeList.subList(0, nodeList.size() / 2));
			insertRouteNodeList.addAll(selectPosJ, nodeList.subList(nodeList.size() / 2, nodeList.size()));
			vehicleId2PlannedRoute.put(selectVehicleId, insertRouteNodeList);
			return true;
		} else {
			System.err.println("select=-1:there is no PDG can be moved with originCost:" + originCost);
			return false;
		}
	}

	/**
	 * 迁移节点对
	 * 
	 * @param nodeList
	 */
	private static void rellocateBlock(ArrayList<Node> nodeList) {
		List<Node> pickupNodeList = (List<Node>) nodeList.subList(0, nodeList.size() / 2);
		List<Node> deliveryNodeList = (List<Node>) nodeList.subList(nodeList.size() / 2, nodeList.size());
		ArrayList<Node> routeNodeList;
		minCostDelta = Double.MAX_VALUE;
		int blockLen = pickupNodeList.size();
		int index = 0;
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = "V_" + String.valueOf(++index);
			Vehicle vehicle = id2VehicleItem.getValue();
			routeNodeList = vehicleId2PlannedRoute.get(vehicleId);
			int nodeListSize = 0;
			HashMap<String, Integer> coupleEndIdxMap = null;
			if (routeNodeList != null) {
				nodeListSize = routeNodeList.size();
				coupleEndIdxMap = getCoupleEndIdxMap(routeNodeList);
			}
			int insertPos = 0;

			if (vehicle.getDes() != null) {
				insertPos = 1;
			}
			for (int i = insertPos; i <= nodeListSize; i++) {
				ArrayList<Node> tempRouteNodeList;
				if (null != routeNodeList) {
					tempRouteNodeList = new ArrayList<Node>(routeNodeList);
				} else {
					tempRouteNodeList = new ArrayList<Node>();
				}
				tempRouteNodeList.addAll(i, pickupNodeList);
				for (int j = blockLen + i; j <= nodeListSize + blockLen; j++) {
					if (j == blockLen + i) {
						tempRouteNodeList.addAll(j, deliveryNodeList);
					} else {
						if (tempRouteNodeList.get(j - 1).getPickupItemList() != null
								&& tempRouteNodeList.get(j - 1).getPickupItemList().size() > 0) {
							String orderItemId = tempRouteNodeList.get(j - 1).getPickupItemList().get(0).getId();
							if (coupleEndIdxMap.get(orderItemId) == null) {
								System.err.println("there is no match PD");
							}

							j = blockLen + coupleEndIdxMap.get(orderItemId) + 1;
							tempRouteNodeList.addAll(j, deliveryNodeList);
						} else if (tempRouteNodeList.get(j - 1).getDeliveryItemList() != null
								&& !tempRouteNodeList.get(j - 1).getDeliveryItemList().isEmpty()) {
							boolean isTerminal = true;
							for (int k = j - 2; k >= 0; k--) {
								if (tempRouteNodeList.get(k).getPickupItemList() != null
										&& !tempRouteNodeList.get(k).getPickupItemList().isEmpty()) {
									int len = tempRouteNodeList.get(j - 1).getDeliveryItemList().size();
									if (tempRouteNodeList.get(j - 1).getDeliveryItemList().get(len - 1).getId()
											.equals(tempRouteNodeList.get(k).getPickupItemList().get(0).getId())) {
										if (k < i) {
											isTerminal = true;
											break;
										} else if (k > i) {
											isTerminal = false;
											break;
										}
									}
								}
							}
							if (isTerminal) {
								break;
							}
							tempRouteNodeList.addAll(j, deliveryNodeList);
						}
					}
					double costValue = cost(tempRouteNodeList, vehicle);
					tempRouteNodeList.removeAll(tempRouteNodeList.subList(j, j + blockLen));
					if (costValue < minCostDelta) {
						minCostDelta = costValue;
						bestInsertPosI = i;
						bestInsertPosJ = j;
						bestInsertVehicleId = vehicleId;
					}
				}
			}
		}
	}

	/**
	 * 获取路径列表中每个P对应的D的位置
	 * 
	 * @param routeNodeList
	 * @return
	 */
	private static HashMap<String, Integer> getCoupleEndIdxMap(ArrayList<Node> routeNodeList) {
		HashMap<String, Integer> coupleEndIdxMap = new HashMap<>();
		for (int i = 0; i < routeNodeList.size(); i++) {
			Node node = routeNodeList.get(i);
			if (node.getPickupItemList() != null && !node.getPickupItemList().isEmpty()) {
				for (int j = i + 1; j < routeNodeList.size(); j++) {
					if (routeNodeList.get(j).getDeliveryItemList() != null
							&& !routeNodeList.get(j).getDeliveryItemList().isEmpty()) {
						int len = routeNodeList.get(j).getDeliveryItemList().size();
						if (node.getPickupItemList().get(0).getId()
								.equals(routeNodeList.get(j).getDeliveryItemList().get(len - 1).getId())) {
							coupleEndIdxMap.put(node.getPickupItemList().get(0).getId(), j);
							break;
						}
					}
				}
			}
		}
		return coupleEndIdxMap;
	}

	/**
	 * 获取可移动的最大PD对
	 * 
	 * @return
	 */
	private static LinkedHashMap<Integer, LinkedHashMap<String, Node>> getUnongoingSuperNode() {
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> unongoingSuperNode = new LinkedHashMap<>();

		int vehicleNo = 1;
		int lsNodePairNum = 0;
		for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
			String vehicleId = id2NodesMap.getKey();
			Vehicle vehicle = id2VehicleMap.get(vehicleId);
			ArrayList<Node> id2NodeList = id2NodesMap.getValue();
			if (id2NodeList != null && id2NodeList.size() > 0) {
				int index = 0;
				if (vehicle.getDes() != null) {
					index = 1;
				}
				ArrayList<Node> pickupNodeHeap = new ArrayList<Node>();
				ArrayList<Integer> pNodeIdxHeap = new ArrayList<>();
				LinkedHashMap<String, Node> PAndDNodeMap = new LinkedHashMap<>();
				int idx = 0;
				String beforePFactoryId = null, beforeDFactoryId = null;
				int beforePNodeIdx = 0, beforeDNodeIdx = 0;
				for (int i = index; i < id2NodeList.size(); i++) {
					Node node = id2NodeList.get(i);
					if (node.getDeliveryItemList() != null && !node.getDeliveryItemList().isEmpty()
							&& node.getPickupItemList() != null && !node.getPickupItemList().isEmpty()) {
						System.err.println("Exist combine Node exception when LS");
					}
					String heapTopOrderItemId = pickupNodeHeap.isEmpty() ? ""
							: pickupNodeHeap.get(0).getPickupItemList().get(0).getId();
					if (node.getDeliveryItemList() != null && node.getDeliveryItemList().size() > 0) {
						int len = node.getDeliveryItemList().size();
						if (node.getDeliveryItemList().get(len - 1).getId().equals(heapTopOrderItemId)) {
							String pickupNodeKey = "V_" + String.valueOf(vehicleNo) + ","
									+ String.valueOf(pNodeIdxHeap.get(0));
							String deliveryNodeKey = "V_" + String.valueOf(vehicleNo) + "," + String.valueOf(i);
							if (PAndDNodeMap.size() >= 2) {
								if (!pickupNodeHeap.get(0).getId().equals(beforePFactoryId)
										|| pNodeIdxHeap.get(0) + 1 != beforePNodeIdx
										|| !node.getId().equals(beforeDFactoryId) || i - 1 != beforeDNodeIdx) {
									unongoingSuperNode.put(lsNodePairNum++, PAndDNodeMap);
									PAndDNodeMap = new LinkedHashMap<>();
								}
							}
							PAndDNodeMap.put(pickupNodeKey, pickupNodeHeap.get(0));
							PAndDNodeMap.put(deliveryNodeKey, node);
							beforePFactoryId = pickupNodeHeap.get(0).getId();
							beforePNodeIdx = pNodeIdxHeap.get(0);
							beforeDFactoryId = node.getId();
							beforeDNodeIdx = i;
							pickupNodeHeap.remove(0);
							pNodeIdxHeap.remove(0);
						}
					}
					if (node.getPickupItemList() != null && node.getPickupItemList().size() > 0) {
						pickupNodeHeap.add(0, node);
						pNodeIdxHeap.add(0, i);
						if (!PAndDNodeMap.isEmpty()) {
							unongoingSuperNode.put(lsNodePairNum++, PAndDNodeMap);
							PAndDNodeMap = new LinkedHashMap<>();
						}
					}
				}
				if (PAndDNodeMap.size() >= 2) {
					unongoingSuperNode.put(lsNodePairNum++, PAndDNodeMap);
				}

			}
			vehicleNo++;
		}
		return unongoingSuperNode;
	}


	/**
	 * 把车辆上的所有节点存起来，除了第一个且已经被当做目标的节点
	 * 
	 * @return unongoingNodeMap
	 */
	private static LinkedHashMap<Integer, LinkedHashMap<String, Node>> getUnongoingNodeMap() {
		LinkedHashMap<Integer, LinkedHashMap<String, Node>> unongoingNodeMap = new LinkedHashMap<>();

		int vehicleNo = 1;
		int lsNodePairNum = 0;
		for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
			String vehicleId = id2NodesMap.getKey();
			Vehicle vehicle = id2VehicleMap.get(vehicleId);
			ArrayList<Node> id2NodeList = id2NodesMap.getValue();
			if (id2NodeList != null && id2NodeList.size() > 0) {
				int index = 0;
				if (vehicle.getDes() != null) {
					index = 1;
				}
				for (int i = index; i < id2NodeList.size(); i++) {
					Node beforeNode = id2NodeList.get(i);
					Node nextNode = null;
					if (beforeNode.getPickupItemList() != null && beforeNode.getPickupItemList().size() > 0) {
						String beforeKey = "V_" + String.valueOf(vehicleNo) + "," + String.valueOf(i);
						String nextKey = "";
						LinkedHashMap<String, Node> PAndDNodeMap = new LinkedHashMap<>();
						String pickupOrderItemId = beforeNode.getPickupItemList().get(0).getId();
						for (int j = i + 1; j < id2NodeList.size(); j++) {
							if (null != id2NodeList.get(j).getDeliveryItemList()
									&& !id2NodeList.get(j).getDeliveryItemList().isEmpty()) {
								int len = id2NodeList.get(j).getDeliveryItemList().size();
								if (id2NodeList.get(j).getDeliveryItemList().get(len - 1).getId()
										.equals(pickupOrderItemId)) {
									nextKey = "V_" + String.valueOf(vehicleNo) + "," + String.valueOf(j);
									nextNode = id2NodeList.get(j);
									break;
								}
							}

						}
						PAndDNodeMap.put(beforeKey, beforeNode);
						PAndDNodeMap.put(nextKey, nextNode);
						unongoingNodeMap.put(lsNodePairNum++, PAndDNodeMap);
					}
				}
			}
			vehicleNo++;
		}
		return unongoingNodeMap;
	}

	/**
	 * 判断是否一个instance的开始,若是，删掉solution.json
	 */
	private static void dealOldSolutionFile() {
		Date ss = new Date();
		ss.setHours(0);
		ss.setMinutes(0);
		ss.setSeconds(0);
		long initialTime = ss.getTime() / 1000;
		if (id2VehicleMap.get("V_1").getGpsUpdateTime() - 600 == initialTime) {
			for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
				if (id2VehicleItem.getValue().getDes() != null) {
					return;
				}
			}
			File file = new File("./algorithm/data_interaction/solution.json");
			if (file.exists()) {
				file.delete();
			}
		}
	}

	/**
	 * 判断订单超过24小时
	 * 
	 * @return
	 */
	private static boolean isOver24Hour() {
		Date ss = new Date();
		ss.setHours(0);
		ss.setMinutes(0);
		ss.setSeconds(0);
		boolean flag = false;
		long initialTime = ss.getTime() / 1000;
		if (id2VehicleMap.get("V_1").getGpsUpdateTime() - 600 - 86400 >= initialTime && newOrderItems.length() == 0) {
			flag = true;
		}
		return flag;
	}

	/* 更新solution.json为这一时段的解 */

	private static void updateSolutionJson() {
		String orderItemsJsonPath = input_directory + "/solution.json";
		String completeOrderItems = "";
		String onVehicleOrderItems = "";
		String ongoingOrderItems = "";
		String unongoingOrderItems = "";
		String unallocatedOrderItems = "";
		String newOrderItems = "";
		String routeBefore = "";
		String routeAfter = "";
		for (Map.Entry<String, OrderItem> onVehicleOrderItem : id2OngoingOrderMap.entrySet()) {
			onVehicleOrderItems += onVehicleOrderItem.getKey() + " ";
		}
		onVehicleOrderItems = onVehicleOrderItems.trim();
		for (Map.Entry<String, OrderItem> unallocatedOrderItem : id2UnallocatedOrderMap.entrySet()) {
			unallocatedOrderItems += unallocatedOrderItem.getKey() + " ";
		}
		unallocatedOrderItems = unallocatedOrderItems.trim();
		File toPathDir = new File(input_directory);
		if (!toPathDir.exists()) {
			toPathDir.mkdir();
		}
		preMatchingItemIds = new ArrayList<>();
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			Vehicle vehicle = id2VehicleItem.getValue();
			if (vehicle.getCarryingItems() == null && vehicle.getDes() != null) {
				List<OrderItem> pickupItemList = vehicle.getDes().getPickupItemList();
				for (OrderItem orderItem : pickupItemList) {
					preMatchingItemIds.add(orderItem.getId());
				}
			}
		}
		for (String orderItemId : preMatchingItemIds) {
			ongoingOrderItems += orderItemId + " ";
		}
		ongoingOrderItems = ongoingOrderItems.trim();
		String[] unallocatedItems = unallocatedOrderItems.split(" ");
		for (String item : unallocatedItems) {
			if (!preMatchingItemIds.contains(item)) {
				unongoingOrderItems += item + " ";
			}
		}
		File f = new File(orderItemsJsonPath);
		if (!f.exists()) {
			deltaT = "0000-0010";
			int vehicleNum = vehicleId2PlannedRoute.size();
			for (int i = 0; i < vehicleNum; i++) {
				String carId = "V_" + String.valueOf(i + 1);
				routeBefore += carId + ":[] ";
			}
			routeBefore = routeBefore.trim();
			routeAfter = getRouteAfter();
			JSONObject solutionJsonObj = new JSONObject(new LinkedHashMap());
			solutionJsonObj.put("no.", "0");
			solutionJsonObj.put("deltaT", deltaT);
			solutionJsonObj.put("complete_order_items", completeOrderItems);
			solutionJsonObj.put("onvehicle_order_items", onVehicleOrderItems);
			solutionJsonObj.put("ongoing_order_items", ongoingOrderItems);
			solutionJsonObj.put("unongoing_order_items", unongoingOrderItems);
			solutionJsonObj.put("unallocated_order_items", unallocatedOrderItems);
			solutionJsonObj.put("new_order_items", unallocatedOrderItems);
			solutionJsonObj.put("used_time", usedTime);
			solutionJsonObj.put("finalCost", cost());
			solutionJsonObj.put("route_before", routeBefore);
			solutionJsonObj.put("route_after", routeAfter);
			try (FileWriter file = new FileWriter(orderItemsJsonPath)) {
				file.write(solutionJsonObj.toJSONString());
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			JSONParser jsonParser = new JSONParser();
			try (FileReader fileReader = new FileReader(orderItemsJsonPath)) {
				JSONObject beforeSolution = (JSONObject) jsonParser.parse(fileReader);
				int no = Integer.valueOf((String) beforeSolution.get("no.")) + 1;
				int fromT = (no + 1) * 10;
				int toT = (no + 1) * 10 + 10;
				String fromTStr = fromT < 10000 ? String.format("%04d", fromT) : String.valueOf(fromT);
				String toTStr = toT < 10000 ? String.format("%04d", toT) : String.valueOf(toT);
				deltaT = fromTStr + "-" + toTStr;
				List<String> list = new ArrayList<String>();
				String[] lastonVehicleItems = ((String) beforeSolution.get("onvehicle_order_items")).split(" ");
				String[] curOnVehicleItems = onVehicleOrderItems.split(" ");
				list = Arrays.asList(curOnVehicleItems);
				for (String lastonVehicleItem : lastonVehicleItems) {
					if (!list.contains(lastonVehicleItem)) {
						completeOrderItems += lastonVehicleItem + " ";
					}
				}
				completeOrderItems = completeOrderItems.trim();
				String[] lastUnallocatedItems = ((String) beforeSolution.get("unallocated_order_items")).split(" ");
				String[] curUnallocatedItems = unallocatedOrderItems.split(" ");
				list = Arrays.asList(lastUnallocatedItems);
				for (String curUnallocatedItem : curUnallocatedItems) {
					if (!list.contains(curUnallocatedItem)) {
						newOrderItems += curUnallocatedItem + " ";
					}
				}
				newOrderItems = newOrderItems.trim();
				routeBefore = (String) beforeSolution.get("route_after");
				routeAfter = getRouteAfter();
				JSONObject solutionJsonObj = new JSONObject(new LinkedHashMap());
				solutionJsonObj.put("no.", String.valueOf(no));
				solutionJsonObj.put("deltaT", deltaT);
				solutionJsonObj.put("complete_order_items", completeOrderItems);
				solutionJsonObj.put("onvehicle_order_items", onVehicleOrderItems);
				solutionJsonObj.put("ongoing_order_items", ongoingOrderItems);
				solutionJsonObj.put("unongoing_order_items", unongoingOrderItems);
				solutionJsonObj.put("unallocated_order_items", unallocatedOrderItems);
				solutionJsonObj.put("new_order_items", newOrderItems);
				solutionJsonObj.put("used_time", usedTime);
				solutionJsonObj.put("finalCost", cost());
				solutionJsonObj.put("route_before", routeBefore);
				solutionJsonObj.put("route_after", routeAfter);
				FileWriter fileWriter = new FileWriter(orderItemsJsonPath);
				fileWriter.write(solutionJsonObj.toJSONString());
				if (null != fileReader)
					fileReader.close();
				if (null != fileWriter)
					fileWriter.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (org.json.simple.parser.ParseException e) {
				e.printStackTrace();
			}
		}
	}

	private static void restoreSceneWithSingleNode() {
		String fileName;
		if (isTest) {
			fileName = debugPeriod + "_solution.json";
		} else {
			fileName = "solution.json";
		}
		String solutionJsonPath = input_directory + '/' + fileName;
		completeOrderItems = "";
		newOrderItems = "";
		onVehicleOrderItems = "";
		unallocatedOrderItems = "";
		routeBefore = "";
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			vehicleId2PlannedRoute.put(vehicleId, null);
		}
		for (Map.Entry<String, OrderItem> onVehicleOrderItem : id2OngoingOrderMap.entrySet()) {
			onVehicleOrderItems += onVehicleOrderItem.getKey() + " ";
		}
		onVehicleOrderItems = onVehicleOrderItems.trim();
		for (Map.Entry<String, OrderItem> unallocatedOrderItem : id2UnallocatedOrderMap.entrySet()) {
			unallocatedOrderItems += unallocatedOrderItem.getKey() + " ";
		}
		unallocatedOrderItems = unallocatedOrderItems.trim();
		File f = new File(solutionJsonPath);
		if (!f.exists()) {
			newOrderItems = unallocatedOrderItems;
			completeOrderItems = "";
			routeBefore = "";
			deltaT = "0000-0010";
		} else {
			JSONParser jsonParser = new JSONParser();
			try (FileReader fileReader = new FileReader(solutionJsonPath)) {
				JSONObject beforeSolution = (JSONObject) jsonParser.parse(fileReader);
				int no = Integer.valueOf((String) beforeSolution.get("no."));
				beforeCost = (double) beforeSolution.get("finalCost");
				int fromT = (no + 1) * 10;
				int toT = (no + 1) * 10 + 10;
				String fromTStr = fromT < 10000 ? String.format("%04d", fromT) : String.valueOf(fromT);
				String toTStr = toT < 10000 ? String.format("%04d", toT) : String.valueOf(toT);
				deltaT = fromTStr + "-" + toTStr;
				List<String> list = new ArrayList<String>();
				String[] lastonVehicleItems = ((String) beforeSolution.get("onvehicle_order_items")).split(" ");
				String[] curOnVehicleItems = onVehicleOrderItems.split(" ");
				list = Arrays.asList(curOnVehicleItems);
				for (String lastonVehicleItem : lastonVehicleItems) {
					if (!list.contains(lastonVehicleItem)) {
						completeOrderItems += lastonVehicleItem + " ";
					}
				}
				completeOrderItems = completeOrderItems.trim();
				String[] lastUnallocatedItems = ((String) beforeSolution.get("unallocated_order_items")).split(" ");
				String[] curUnallocatedItems = unallocatedOrderItems.split(" ");
				list = Arrays.asList(lastUnallocatedItems);
				for (String curUnallocatedItem : curUnallocatedItems) {
					if (!list.contains(curUnallocatedItem)) {
						newOrderItems += curUnallocatedItem + " ";
					}
				}
				newOrderItems = newOrderItems.trim();
				routeBefore = (String) beforeSolution.get("route_after");
				if (null != fileReader)
					fileReader.close();
				String[] routeBeforeSplit = routeBefore.split("V");
				String[] completeItemsArray = completeOrderItems.split(" ");
				for (int i = 1; i < routeBeforeSplit.length; i++) {
					routeBeforeSplit[i] = routeBeforeSplit[i].trim();
					int strLen = routeBeforeSplit[i].split(":")[1].length();
					String numStr = routeBeforeSplit[i].split(":")[0];
					String vehicleId = "V_" + numStr.substring(1, numStr.length());
					if (strLen < 3) {
						vehicleId2PlannedRoute.put(vehicleId, null);
						continue;
					}
					String routeNodesStr = routeBeforeSplit[i].split(":")[1];
					String[] routeNodes = routeNodesStr.substring(1, routeNodesStr.length() - 1).split(" ");
					List<String> nodeList = new ArrayList(Arrays.asList(routeNodes));
					for (int j = 0; j < nodeList.size(); j++) {
						String itemId = nodeList.get(j).split("_")[1];
						if (nodeList.get(j).substring(0, 1).equals("d")) {
							for (int k = 0; k < completeItemsArray.length; k++) {
								if (completeItemsArray[k].equals(itemId)) {
									nodeList.remove(j);
									j--;
									break;
								}
							}
						}
					}
					for (int j = 0; j < nodeList.size(); j++) {
						String itemId = nodeList.get(j).split("_")[1];
						if (nodeList.get(j).substring(0, 1).equals("p")) {
							for (int k = 0; k < curOnVehicleItems.length; k++) {
								if (curOnVehicleItems[k].equals(itemId)) {
									nodeList.remove(j);
									j--;
									break;
								}
							}
						}
					}
					if (nodeList.size() > 0) {
						ArrayList<Node> planRoute = new ArrayList<Node>();
						for (int j = 0; j < nodeList.size(); j++) {
							List<OrderItem> deliveryItemList = new ArrayList<OrderItem>();
							List<OrderItem> pickupItemList = new ArrayList<OrderItem>();
							OrderItem deliveryOrPickupItem = null;
							String op, orderItemId;
							int opItemNum = 0;
							op = nodeList.get(j).substring(0, 1);
							String[] opNumStr = nodeList.get(j).split("_");
							int len = opNumStr[0].length();
							opItemNum = Integer.valueOf(opNumStr[0].substring(1, len));
							String[] opCombine = nodeList.get(j).split("_");
							orderItemId = opCombine[1];
							String[] indexStr = orderItemId.split("-");
							int idEndNumber = Integer.valueOf(indexStr[1]);
							if (op.equals("d")) {
								for (int k = 0; k < opItemNum; k++) {
									deliveryOrPickupItem = id2OrderItemMap.get(orderItemId);
									deliveryItemList.add(deliveryOrPickupItem);
									orderItemId = orderItemId.split("-")[0] + "-" + String.valueOf(--idEndNumber);
								}
							} else {
								for (int k = 0; k < opItemNum; k++) {
									deliveryOrPickupItem = id2OrderItemMap.get(orderItemId);
									pickupItemList.add(deliveryOrPickupItem);
									orderItemId = orderItemId.split("-")[0] + "-" + String.valueOf(++idEndNumber);
								}
							}
							String factoryId;
							if (op.equals("d")) {
								factoryId = deliveryOrPickupItem.getDeliveryFactoryId();
							} else {
								factoryId = deliveryOrPickupItem.getPickupFactoryId();
							}
							Factory factory = id2FactoryMap.get(factoryId);
							Node node = new Node(factoryId, deliveryItemList, pickupItemList, factory.getLng(),
									factory.getLat());
							planRoute.add(node);
						}
						if (nodeList.size() > 0) {
							vehicleId2PlannedRoute.put(vehicleId, planRoute);
						}

					}
				}

			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (org.json.simple.parser.ParseException e) {
				e.printStackTrace();
			}
		}

	}

	private static void restoreSceneDelayDispatchTime() {
		String fileName;
		if (isTest) {
			fileName = debugPeriod + "_solution.json";
		} else {
			fileName = "solution.json";
		}

		String solutionJsonPath = input_directory + '/' + fileName;
		completeOrderItems = "";
		newOrderItems = "";
		onVehicleOrderItems = "";
		unallocatedOrderItems = "";
		routeBefore = "";
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			vehicleId2PlannedRoute.put(vehicleId, null);
		}
		for (Map.Entry<String, OrderItem> onVehicleOrderItem : id2OngoingOrderMap.entrySet()) {
			onVehicleOrderItems += onVehicleOrderItem.getKey() + " ";
		}
		onVehicleOrderItems = onVehicleOrderItems.trim();
		for (Map.Entry<String, OrderItem> unallocatedOrderItem : id2UnallocatedOrderMap.entrySet()) {
			unallocatedOrderItems += unallocatedOrderItem.getKey() + " ";
		}
		unallocatedOrderItems = unallocatedOrderItems.trim();
		File f = new File(solutionJsonPath);
		if (!f.exists()) {
			newOrderItems = unallocatedOrderItems;
			completeOrderItems = "";
			routeBefore = "";
			deltaT = "0000-0010";
		} else {
			JSONParser jsonParser = new JSONParser();
			try (FileReader fileReader = new FileReader(solutionJsonPath)) {
				JSONObject beforeSolution = (JSONObject) jsonParser.parse(fileReader);
				int no = Integer.valueOf((String) beforeSolution.get("no."));
				int fromT = (no + 1) * 10;
				int toT = (no + 1) * 10 + 10;
				String fromTStr = fromT < 10000 ? String.format("%04d", fromT) : String.valueOf(fromT);
				String toTStr = toT < 10000 ? String.format("%04d", toT) : String.valueOf(toT);
				deltaT = fromTStr + "-" + toTStr;
				List<String> list = new ArrayList<String>();
				String[] lastonVehicleItems = ((String) beforeSolution.get("onvehicle_order_items")).split(" ");
				String[] curOnVehicleItems = onVehicleOrderItems.split(" ");
				list = Arrays.asList(curOnVehicleItems);
				for (String lastonVehicleItem : lastonVehicleItems) {
					if (!list.contains(lastonVehicleItem)) {
						completeOrderItems += lastonVehicleItem + " ";
					}
				}
				completeOrderItems = completeOrderItems.trim();
				String[] lastUnallocatedItems = ((String) beforeSolution.get("unallocated_order_items")).split(" ");
				String[] curUnallocatedItems = unallocatedOrderItems.split(" ");
				list = Arrays.asList(lastUnallocatedItems);
				for (String curUnallocatedItem : curUnallocatedItems) {
					if (!list.contains(curUnallocatedItem)) {
						newOrderItems += curUnallocatedItem + " ";
					}
				}
				newOrderItems = newOrderItems.trim();
				routeBefore = (String) beforeSolution.get("route_after");
				if (null != fileReader)
					fileReader.close();
				String[] routeBeforeSplit = routeBefore.split("V");
				String[] completeItemsArray = completeOrderItems.split(" ");
				for (int i = 1; i < routeBeforeSplit.length; i++) {
					routeBeforeSplit[i] = routeBeforeSplit[i].trim();
					int strLen = routeBeforeSplit[i].split(":")[1].length();
					String numStr = routeBeforeSplit[i].split(":")[0];
					String vehicleId = "V_" + numStr.substring(1, numStr.length());
					if (strLen < 3) {
						vehicleId2PlannedRoute.put(vehicleId, null);
						continue;
					}
					String routeNodesStr = routeBeforeSplit[i].split(":")[1];
					String[] routeNodes = routeNodesStr.substring(1, routeNodesStr.length() - 1).split(" ");
					List<String> nodeList = new ArrayList(Arrays.asList(routeNodes));
					String[] deliveryAndPickupItemsStr = nodeList.get(0).split("&");
					String deliveryItemsId = "";
					String pickupItemsId = "";
					String[] dItemIdArrayTmp;
					String[] pItemIdArrayTmp;
					String[] dItemIdArray = null;
					String[] pItemIdArray = null;
					String[] dOrderItemNum = null, pOrderItemNum = null;
					if (deliveryAndPickupItemsStr.length == 2) {
						deliveryItemsId = deliveryAndPickupItemsStr[0];
						pickupItemsId = deliveryAndPickupItemsStr[1];
						dItemIdArrayTmp = deliveryItemsId.split("d");
						dItemIdArray = new String[dItemIdArrayTmp.length - 1];
						dOrderItemNum = new String[dItemIdArrayTmp.length - 1];
						pItemIdArrayTmp = pickupItemsId.split("p");
						pItemIdArray = new String[pItemIdArrayTmp.length - 1];
						pOrderItemNum = new String[pItemIdArrayTmp.length - 1];
						for (int j = 1; j < dItemIdArrayTmp.length; j++) {
							dOrderItemNum[j - 1] = dItemIdArrayTmp[j].split("_")[0];
							dItemIdArray[j - 1] = dItemIdArrayTmp[j].split("_")[1];
						}
						for (int j = 1; j < pItemIdArrayTmp.length; j++) {
							pOrderItemNum[j - 1] = pItemIdArrayTmp[j].split("_")[0];
							pItemIdArray[j - 1] = pItemIdArrayTmp[j].split("_")[1];
						}
					} else {
						if (deliveryAndPickupItemsStr[0].charAt(0) == 'd') {
							deliveryItemsId = deliveryAndPickupItemsStr[0];
							dItemIdArrayTmp = deliveryItemsId.split("d");
							dItemIdArray = new String[dItemIdArrayTmp.length - 1];
							dOrderItemNum = new String[dItemIdArrayTmp.length - 1];
							for (int j = 1; j < dItemIdArrayTmp.length; j++) {
								dOrderItemNum[j - 1] = dItemIdArrayTmp[j].split("_")[0];
								dItemIdArray[j - 1] = dItemIdArrayTmp[j].split("_")[1];
							}
						} else {
							pickupItemsId = deliveryAndPickupItemsStr[0];
							pItemIdArrayTmp = pickupItemsId.split("p");
							pItemIdArray = new String[pItemIdArrayTmp.length - 1];
							pOrderItemNum = new String[pItemIdArrayTmp.length - 1];
							for (int j = 1; j < pItemIdArrayTmp.length; j++) {
								pOrderItemNum[j - 1] = pItemIdArrayTmp[j].split("_")[0];
								pItemIdArray[j - 1] = pItemIdArrayTmp[j].split("_")[1];
							}
						}
					}
					String deliveryOrderItemsStr = "", pickupOrderItemsStr = "", resultOrderItemsStr = "";
					int dNum = 0, pNum = 0;
					if (deliveryItemsId.length() != 0) {
						for (int j = 0; j < dItemIdArray.length; j++) {
							for (int k = 0; k < completeItemsArray.length; k++) {
								if (completeItemsArray[k].equals(dItemIdArray[j])) {
									dNum++;
									break;
								}
							}
							if (dNum - 1 != j) {
								break;
							}
						}
						if (dNum != dItemIdArray.length) {
							for (int j = dNum; j < dItemIdArray.length; j++) {
								deliveryOrderItemsStr += "d" + dOrderItemNum[j] + "_" + dItemIdArray[j];
							}
						}
						if (dNum == dItemIdArray.length && pickupItemsId.length() != 0) {
							for (int j = 0; j < pItemIdArray.length; j++) {
								for (int k = 0; k < curOnVehicleItems.length; k++) {
									if (curOnVehicleItems[k].equals(pItemIdArray[j])) {
										pNum++;
										break;
									}
								}
								if (pNum - 1 != j) {
									break;
								}
							}
						}
						if (pItemIdArray != null && pNum != pItemIdArray.length) {
							for (int j = pNum; j < pItemIdArray.length; j++) {
								pickupOrderItemsStr += "p" + pOrderItemNum[j] + "_" + pItemIdArray[j];
							}
						}
					} else if (pickupItemsId.length() != 0) {
						for (int j = 0; j < pItemIdArray.length; j++) {
							for (int k = 0; k < curOnVehicleItems.length; k++) {
								if (curOnVehicleItems[k].equals(pItemIdArray[j])) {
									pNum++;
									break;
								}
							}
							if (pNum - 1 != j) {
								break;
							}
						}
						if (pNum != pItemIdArray.length) {
							for (int j = pNum; j < pItemIdArray.length; j++) {
								pickupOrderItemsStr += "p" + pOrderItemNum[j] + "_" + pItemIdArray[j];
							}
						}
					}
					if (deliveryOrderItemsStr.length() == 0 && pickupOrderItemsStr.length() == 0) {
						nodeList.remove(0);
					} else {
						if (deliveryOrderItemsStr.length() != 0) {
							resultOrderItemsStr += deliveryOrderItemsStr;
							if (pickupOrderItemsStr.length() != 0) {
								resultOrderItemsStr += "&" + pickupOrderItemsStr;
							}
						} else if (pickupOrderItemsStr.length() != 0) {
							resultOrderItemsStr += pickupOrderItemsStr;
						}
						nodeList.set(0, resultOrderItemsStr);
					} 

					if (nodeList.size() > 0) {
						ArrayList<Node> planRoute = new ArrayList<Node>();
						for (int j = 0; j < nodeList.size(); j++) {
							List<OrderItem> deliveryItemList = new ArrayList<OrderItem>();
							List<OrderItem> pickupItemList = new ArrayList<OrderItem>();
							OrderItem deliveryOrPickupItem = null, pickupOrderItem = null;
							boolean isBothOp = false;
							char op1;
							deliveryAndPickupItemsStr = nodeList.get(j).split("&");
							if (deliveryAndPickupItemsStr[0].charAt(0) == 'd') {
								op1 = 'd';
							} else {
								op1 = 'p';
							}
							if (deliveryAndPickupItemsStr.length == 2) {
								isBothOp = true;
								deliveryItemsId = deliveryAndPickupItemsStr[0];
								pickupItemsId = deliveryAndPickupItemsStr[1];
							} else {
								if (op1 == 'd') {
									deliveryItemsId = deliveryAndPickupItemsStr[0];
								} else {
									pickupItemsId = deliveryAndPickupItemsStr[0];
								}
							}
							int[] dIdEndNumber;
							int[] pIdEndNumber;
							int[] dItemNum;
							int[] pItemNum;
							if (!isBothOp) {
								if (op1 == 'd') {
									dItemIdArrayTmp = deliveryItemsId.split("d");
									dItemIdArray = new String[dItemIdArrayTmp.length - 1];
									dItemNum = new int[dItemIdArrayTmp.length - 1];
									dIdEndNumber = new int[dItemIdArrayTmp.length - 1];
									for (int k = 1; k < dItemIdArrayTmp.length; k++) {
										dItemNum[k - 1] = Integer.valueOf(dItemIdArrayTmp[k].split("_")[0]);
										dItemIdArray[k - 1] = dItemIdArrayTmp[k].split("_")[1];
										dIdEndNumber[k - 1] = Integer.valueOf(dItemIdArray[k - 1].split("-")[1]);
									}
									for (int k = 0; k < dItemIdArray.length; k++) {
										for (int n = 0; n < dItemNum[k]; n++) {
											deliveryOrPickupItem = id2OrderItemMap.get(dItemIdArray[k]);
											deliveryItemList.add(deliveryOrPickupItem);
											dItemIdArray[k] = dItemIdArray[k].split("-")[0] + "-"
													+ String.valueOf(--dIdEndNumber[k]);
										}
									}
								} else {
									pItemIdArrayTmp = pickupItemsId.split("p");
									pItemIdArray = new String[pItemIdArrayTmp.length - 1];
									pItemNum = new int[pItemIdArrayTmp.length - 1];
									pIdEndNumber = new int[pItemIdArrayTmp.length - 1];
									for (int k = 1; k < pItemIdArrayTmp.length; k++) {
										String str = pItemIdArrayTmp[k].split("_")[0];
										pItemNum[k - 1] = Integer.valueOf(str);
										pItemIdArray[k - 1] = pItemIdArrayTmp[k].split("_")[1];
										pIdEndNumber[k - 1] = Integer.valueOf(pItemIdArray[k - 1].split("-")[1]);
									}
									for (int k = 0; k < pItemIdArray.length; k++) {
										for (int n = 0; n < pItemNum[k]; n++) {
											deliveryOrPickupItem = id2OrderItemMap.get(pItemIdArray[k]);
											pickupItemList.add(deliveryOrPickupItem);
											pItemIdArray[k] = pItemIdArray[k].split("-")[0] + "-"
													+ String.valueOf(++pIdEndNumber[k]);
										}
									}
								}

							} else {
								dItemIdArrayTmp = deliveryItemsId.split("d");
								dItemIdArray = new String[dItemIdArrayTmp.length - 1];
								dItemNum = new int[dItemIdArrayTmp.length - 1];
								dIdEndNumber = new int[dItemIdArrayTmp.length - 1];
								for (int k = 1; k < dItemIdArrayTmp.length; k++) {
									dItemNum[k - 1] = Integer.valueOf(dItemIdArrayTmp[k].split("_")[0]);
									dItemIdArray[k - 1] = dItemIdArrayTmp[k].split("_")[1];
									dIdEndNumber[k - 1] = Integer.valueOf(dItemIdArray[k - 1].split("-")[1]);
								}
								for (int k = 0; k < dItemIdArray.length; k++) {
									for (int n = 0; n < dItemNum[k]; n++) {
										deliveryOrPickupItem = id2OrderItemMap.get(dItemIdArray[k]);
										deliveryItemList.add(deliveryOrPickupItem);
										dItemIdArray[k] = dItemIdArray[k].split("-")[0] + "-"
												+ String.valueOf(--dIdEndNumber[k]);
									}
								}
								pItemIdArrayTmp = pickupItemsId.split("p");
								pItemIdArray = new String[pItemIdArrayTmp.length - 1];
								pItemNum = new int[pItemIdArrayTmp.length - 1];
								pIdEndNumber = new int[pItemIdArrayTmp.length - 1];
								for (int k = 1; k < pItemIdArrayTmp.length; k++) {
									pItemNum[k - 1] = Integer.valueOf(pItemIdArrayTmp[k].split("_")[0]);
									pItemIdArray[k - 1] = pItemIdArrayTmp[k].split("_")[1];
									pIdEndNumber[k - 1] = Integer.valueOf(pItemIdArray[k - 1].split("-")[1]);
								}
								for (int k = 0; k < pItemIdArray.length; k++) {
									for (int n = 0; n < pItemNum[k]; n++) {
										pickupOrderItem = id2OrderItemMap.get(pItemIdArray[k]);
										pickupItemList.add(pickupOrderItem);
										pItemIdArray[k] = pItemIdArray[k].split("-")[0] + "-"
												+ String.valueOf(++pIdEndNumber[k]);
									}
								}
							}
							String factoryId;
							if (op1 == 'd') {
								factoryId = deliveryOrPickupItem.getDeliveryFactoryId();
							} else {
								factoryId = deliveryOrPickupItem.getPickupFactoryId();
							}
							Factory factory = id2FactoryMap.get(factoryId);
							Node node = new Node(factoryId, deliveryItemList, pickupItemList, factory.getLng(),
									factory.getLat());
							planRoute.add(node);
						}
						if (nodeList.size() > 0) {
							vehicleId2PlannedRoute.put(vehicleId, planRoute);
						}

					}
				}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (org.json.simple.parser.ParseException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 用Chepeast Insert插入新订单
	 */
	private static void dispatchNewOrders() {
		String[] newOrderItemIdArray = null;
		if (newOrderItems.length() > 0) {
			newOrderItemIdArray = newOrderItems.split(" ");
		}
		LinkedHashMap<String, ArrayList<OrderItem>> orderId2Items = new LinkedHashMap<>();
		if (newOrderItemIdArray != null && newOrderItemIdArray.length > 0) {
			for (String newOrderItemId : newOrderItemIdArray) {
				OrderItem newOrderItem = id2UnallocatedOrderMap.get(newOrderItemId);
				String orderId = newOrderItem.getOrderId();
				ArrayList<OrderItem> orderItemList = null;
				if (!orderId2Items.containsKey(orderId)) {
					orderItemList = new ArrayList<OrderItem>();
					orderItemList.add(newOrderItem);
					orderId2Items.put(orderId, orderItemList);
				} else {
					ArrayList<OrderItem> itemList = orderId2Items.get(orderId);
					itemList.add(newOrderItem);
					orderId2Items.put(orderId, itemList);
				}
			}
			double capacity = 0;
			for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
				Vehicle vehicle = id2VehicleItem.getValue();
				capacity = vehicle.getBoardCapacity();
				break;
			}
			LinkedHashMap<String, ArrayList<OrderItem>> overCapacityOrderId2Items = new LinkedHashMap<>();
			for (Map.Entry<String, ArrayList<OrderItem>> orderItemList : orderId2Items.entrySet()) {
				int vehicleNum = id2VehicleMap.size();
				ArrayList<OrderItem> newOrderItems = orderItemList.getValue();
				double newOrderItemsCapacity = 0;
				for (OrderItem orderItem : newOrderItems) {
					newOrderItemsCapacity += orderItem.getDemand();
				}
				if (newOrderItemsCapacity > capacity) {
					double curDemand = 0;
					List<OrderItem> tmpItems = new ArrayList<OrderItem>();
					for (OrderItem item : newOrderItems) {
						if (curDemand + item.getDemand() > capacity) {
							ArrayList<Node> nodeList = (ArrayList<Node>) createPickupAndDeliveryNodes(tmpItems);
							dispatchNewOrdersWithImproveCI(nodeList);
							ArrayList<Node> routeNodeList = vehicleId2PlannedRoute.get(bestInsertVehicleId);
							if (isExhaustive) {
								routeNodeList = new ArrayList<>(bestNodeList);
							} else {
								if (null == routeNodeList) {
									routeNodeList = new ArrayList<Node>();
								}
								Node newOrderPickupNode = nodeList.get(0);
								Node newOrderDeliveryNode = nodeList.get(1);
								routeNodeList.add(bestInsertPosI, newOrderPickupNode);
								routeNodeList.add(bestInsertPosJ, newOrderDeliveryNode);
							}
							vehicleId2PlannedRoute.put(bestInsertVehicleId, routeNodeList);

							tmpItems.clear();
							curDemand = 0;
						}
						tmpItems.add(item);
						curDemand += item.getDemand();
					}
					if (tmpItems.size() > 0) {
						ArrayList<Node> nodeList = (ArrayList<Node>) createPickupAndDeliveryNodes(tmpItems);
						dispatchNewOrdersWithImproveCI(nodeList);
						ArrayList<Node> routeNodeList = vehicleId2PlannedRoute.get(bestInsertVehicleId);
						if (isExhaustive) {
							routeNodeList = new ArrayList<>(bestNodeList);
						} else {
							if (null == routeNodeList) {
								routeNodeList = new ArrayList<Node>();
							}
							Node newOrderPickupNode = nodeList.get(0);
							Node newOrderDeliveryNode = nodeList.get(1);
							routeNodeList.add(bestInsertPosI, newOrderPickupNode);
							routeNodeList.add(bestInsertPosJ, newOrderDeliveryNode);
						}
						vehicleId2PlannedRoute.put(bestInsertVehicleId, routeNodeList);
					}
				} else {
					ArrayList<Node> nodeList = (ArrayList<Node>) createPickupAndDeliveryNodes(newOrderItems);
					dispatchNewOrdersWithImproveCI(nodeList);
					ArrayList<Node> routeNodeList = vehicleId2PlannedRoute.get(bestInsertVehicleId);
					if (isExhaustive) {
						routeNodeList = new ArrayList<>(bestNodeList);
					} else {
						if (null == routeNodeList) {
							routeNodeList = new ArrayList<Node>();
						}
						Node newOrderPickupNode = nodeList.get(0);
						Node newOrderDeliveryNode = nodeList.get(1);
						routeNodeList.add(bestInsertPosI, newOrderPickupNode);
						routeNodeList.add(bestInsertPosJ, newOrderDeliveryNode);
					}
					vehicleId2PlannedRoute.put(bestInsertVehicleId, routeNodeList);
				}
			}
		}
	}

	/**
	 * n节点数 获取订单数为2，3，4时，即4，6，8个节点的有效全排列
	 */

	private static boolean isPermutationFeasible(int[] elements, int n) {
		n = n / 2;
		boolean isFeasible = true;
		ArrayList<Integer> heap = new ArrayList<>();
		for (int e : elements) {
			if (e < n) {
				heap.add(e);
			} else {
				if (heap.size() > 0) {
					int topValue = heap.get(heap.size() - 1);
					if (topValue + e != 2 * n - 1) {
						isFeasible = false;
						break;
					}
					heap.remove(heap.size() - 1);
				} else {
					isFeasible = false;
					break;
				}
			}
		}
		return isFeasible;
	}

	public static void printAllRecursive(int n, int[] elements, char delimiter) {

		if (n == 1) {
			printArray(elements);
		} else {
			for (int i = 0; i < n - 1; i++) {
				printAllRecursive(n - 1, elements, delimiter);
				if (n % 2 == 0) {
					swap(elements, i, n - 1);
				} else {
					swap(elements, 0, n - 1);
				}
			}
			printAllRecursive(n - 1, elements, delimiter);
		}
	}

	private static void printArray(int[] input) {
		System.out.print('\n');
		for (int i = 0; i < input.length; i++) {
			System.out.print(input[i]);
		}
	}

	private static void swap(int[] input, int a, int b) {
		int tmp = input[a];
		input[a] = input[b];
		input[b] = tmp;
	}

	/**
	 * 对路径解进行合并节点
	 */
	private static void MergeNode() {
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			Vehicle vehicle = id2VehicleItem.getValue();
			ArrayList<Node> orignPlannedRoute = vehicleId2PlannedRoute.get(vehicleId);
			if (orignPlannedRoute != null && orignPlannedRoute.size() > 0) {
				Node beforeNode = orignPlannedRoute.get(0);
				for (int i = 1; i < orignPlannedRoute.size(); i++) {
					Node nextNode = orignPlannedRoute.get(i);
					if (beforeNode.getId().equals(nextNode.getId())) {
						if (nextNode.getPickupItemList() != null && nextNode.getPickupItemList().size() > 0) {
							for (OrderItem orderItem : nextNode.getPickupItemList()) {
								beforeNode.getPickupItemList().add(orderItem);
							}
						}
						if (nextNode.getDeliveryItemList() != null && nextNode.getDeliveryItemList().size() > 0) {
							for (OrderItem orderItem : nextNode.getDeliveryItemList()) {
								beforeNode.getDeliveryItemList().add(orderItem);
							}
						}
						orignPlannedRoute.remove(i);
						i--;
					}
					beforeNode = orignPlannedRoute.get(i);
				}
			}
			vehicleId2PlannedRoute.put(vehicleId, orignPlannedRoute);
		}
	}

	/**
	 * 返回订单CI最低代价插入车辆的全局变量：车辆编号bestInsertVehicleId、
	 * 取、送货节点插入位置bestInsertPosI、bestInsertPosJ,和最小插入适应值增量minCostDelta
	 * 
	 * @param nodeList 待插入的取送货两个节点
	 */
	private static void dispatchNewOrdersWithImproveCI(ArrayList<Node> nodeList) {
		isExhaustive = false;
		Node newOrderPickupNode = nodeList.get(0);
		Node newOrderDeliveryNode = nodeList.get(1);
		ArrayList<Node> routeNodeList;
		minCostDelta = Double.MAX_VALUE;
		int index = 0;
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = "V_" + String.valueOf(++index);
			Vehicle vehicle = id2VehicleItem.getValue();
			routeNodeList = vehicleId2PlannedRoute.get(vehicleId);
			int nodeListSize = 0;
			if (routeNodeList != null) {
				nodeListSize = routeNodeList.size();
			}
			int insertPos = 0;
			boolean firstNodeIsLocked = false, firstNodeIsDelivery = false, firstNodeIsPickup = false;
			int modelNodesNum = nodeListSize + 2;
			int beginDeliveryNodeNum = 0;
			int firstMergeNodeNum = 0;
			if (vehicle.getDes() != null) {
				if (!newOrderPickupNode.getId().equals(vehicle.getDes().getId())) {
					insertPos = 1;
				}
				firstNodeIsLocked = true;
				if (vehicle.getDes().getDeliveryItemList() != null
						&& vehicle.getDes().getDeliveryItemList().size() > 0) {
					firstNodeIsDelivery = true;
				}
				if (vehicle.getDes().getPickupItemList() != null && vehicle.getDes().getPickupItemList().size() > 0) {
					firstNodeIsPickup = true;
				}
				if (null != routeNodeList) {
					for (Node node : routeNodeList) {
						if (!vehicle.getDes().getId().equals(node.getId())) {
							break;
						}
						firstMergeNodeNum++;
					}
				}
			}

			modelNodesNum -= firstMergeNodeNum;
			ArrayList<Node> modleNodeList = new ArrayList<Node>();
			ArrayList<Node> exghaustiveRouteNodeList = new ArrayList<Node>();
			ArrayList<Node> cpRouteNodeList = null;
			if (routeNodeList != null) {
				cpRouteNodeList = new ArrayList<Node>(routeNodeList);
			}

			int emptyPosNum = 0;
			if (modelNodesNum <= 8) {
				int beginIdx = 0;
				if (firstMergeNodeNum > 0) {
					beginIdx = firstMergeNodeNum;
					while (firstMergeNodeNum > 0) {
						exghaustiveRouteNodeList.add(cpRouteNodeList.get(0));
						cpRouteNodeList.remove(0);
						firstMergeNodeNum--;
					}
				}

				int count = 0;
				for (int i = 0; cpRouteNodeList != null && i < cpRouteNodeList.size(); i++) {
					Node pickupNode, deliveryNode = null;
					String orderItemId = "";
					if (cpRouteNodeList.get(i).getPickupItemList() != null
							&& cpRouteNodeList.get(i).getPickupItemList().size() > 0) {
						orderItemId = cpRouteNodeList.get(i).getPickupItemList().get(0).getId();
						pickupNode = cpRouteNodeList.get(i);
						cpRouteNodeList.remove(i);
						for (int j = i; j < cpRouteNodeList.size(); j++) {
							if (cpRouteNodeList.get(j).getDeliveryItemList() != null
									&& cpRouteNodeList.get(j).getDeliveryItemList().size() > 0) {
								int len = cpRouteNodeList.get(j).getDeliveryItemList().size();
								String itemId = cpRouteNodeList.get(j).getDeliveryItemList().get(len - 1).getId();
								if (orderItemId.equals(itemId)) {
									deliveryNode = cpRouteNodeList.get(j);
									cpRouteNodeList.remove(j);
									break;
								}
							}
						}
						modleNodeList.add(count, pickupNode);
						modleNodeList.add(count + 1, deliveryNode);
						i--;
						count++;
					}
				}
				modleNodeList.add(count++, newOrderPickupNode);
				modleNodeList.add(count, newOrderDeliveryNode);
				emptyPosNum = cpRouteNodeList == null ? 0 : cpRouteNodeList.size();
				while (cpRouteNodeList != null && cpRouteNodeList.size() > 0) {
					modleNodeList.add(cpRouteNodeList.get(0));
					cpRouteNodeList.remove(0);
				}
				modelNodesNum = modleNodeList.size() + emptyPosNum;
			}
			if (modelNodesNum <= 8) {
				if (modelNodesNum == 2) {
					exghaustiveRouteNodeList.add(newOrderPickupNode);
					exghaustiveRouteNodeList.add(newOrderDeliveryNode);
					double costValue = cost(exghaustiveRouteNodeList, vehicle);
					if (costValue < minCostDelta) {
						minCostDelta = costValue;
						bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
						bestInsertVehicleId = vehicleId;
						isExhaustive = true;
					}
				} else if (modelNodesNum == 4) {
					for (int i = 0; i < Param.modle4.length; i++) {
						if (emptyPosNum == 1) {
							if (Param.modle4[i][0] == 0) {
								for (int j = 1; j < 4; j++) {
									exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle4[i][j] - 1));
								}
								double costValue = cost(exghaustiveRouteNodeList, vehicle);
								if (costValue < minCostDelta) {
									minCostDelta = costValue;
									bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
									bestInsertVehicleId = vehicleId;
									isExhaustive = true;
								}
								exghaustiveRouteNodeList
										.subList(exghaustiveRouteNodeList.size() - 3, exghaustiveRouteNodeList.size())
										.clear();
							}
						} else {
							for (int j = 0; j < 4; j++) {
								exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle4[i][j]));
							}
							double costValue = cost(exghaustiveRouteNodeList, vehicle);
							if (costValue < minCostDelta) {
								minCostDelta = costValue;
								bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
								bestInsertVehicleId = vehicleId;
								isExhaustive = true;
							}
							exghaustiveRouteNodeList
									.subList(exghaustiveRouteNodeList.size() - 4, exghaustiveRouteNodeList.size())
									.clear();
						}
					}
				} else if (modelNodesNum == 6) {
					for (int i = 0; i < Param.modle6.length; i++) {
						if (emptyPosNum == 1) {
							if (Param.modle6[i][0] == 0) {
								for (int j = 1; j < 6; j++) {
									exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle6[i][j] - 1));
								}
								double costValue = cost(exghaustiveRouteNodeList, vehicle);
								if (costValue < minCostDelta) {
									minCostDelta = costValue;
									bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
									bestInsertVehicleId = vehicleId;
									isExhaustive = true;
								}
								exghaustiveRouteNodeList
										.subList(exghaustiveRouteNodeList.size() - 5, exghaustiveRouteNodeList.size())
										.clear();
							}
						} else if (emptyPosNum == 2) {
							if (Param.modle6[i][0] == 0 && Param.modle6[i][1] == 1) {
								for (int j = 2; j < 6; j++) {
									exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle6[i][j] - 2));
								}
								double costValue = cost(exghaustiveRouteNodeList, vehicle);
								if (costValue < minCostDelta) {
									minCostDelta = costValue;
									bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
									bestInsertVehicleId = vehicleId;
									isExhaustive = true;
								}
								exghaustiveRouteNodeList
										.subList(exghaustiveRouteNodeList.size() - 4, exghaustiveRouteNodeList.size())
										.clear();
							}
						} else {
							for (int j = 0; j < 6; j++) {
								exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle6[i][j]));
							}
							double costValue = cost(exghaustiveRouteNodeList, vehicle);
							if (costValue < minCostDelta) {
								minCostDelta = costValue;
								bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
								bestInsertVehicleId = vehicleId;
								isExhaustive = true;
							}
							exghaustiveRouteNodeList
									.subList(exghaustiveRouteNodeList.size() - 6, exghaustiveRouteNodeList.size())
									.clear();
						}
					}
				} else if (modelNodesNum == 8) {
					for (int i = 0; i < Param.modle8.length; i++) {
						if (emptyPosNum == 1) {
							if (Param.modle8[i][0] == 0) {
								for (int j = 1; j < 8; j++) {
									exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle8[i][j] - 1));
								}
								double costValue = cost(exghaustiveRouteNodeList, vehicle);
								if (costValue < minCostDelta) {
									minCostDelta = costValue;
									bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
									bestInsertVehicleId = vehicleId;
									isExhaustive = true;
								}
								exghaustiveRouteNodeList
										.subList(exghaustiveRouteNodeList.size() - 7, exghaustiveRouteNodeList.size())
										.clear();
							}
						} else if (emptyPosNum == 2) {
							if (Param.modle8[i][0] == 0 && Param.modle8[i][1] == 1) {
								for (int j = 2; j < 8; j++) {
									exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle8[i][j] - 2));
								}
								double costValue = cost(exghaustiveRouteNodeList, vehicle);
								if (costValue < minCostDelta) {
									minCostDelta = costValue;
									bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
									bestInsertVehicleId = vehicleId;
									isExhaustive = true;
								}
								exghaustiveRouteNodeList
										.subList(exghaustiveRouteNodeList.size() - 6, exghaustiveRouteNodeList.size())
										.clear();
							}
						} else if (emptyPosNum == 3) {
							if (Param.modle8[i][0] == 0 && Param.modle8[i][1] == 1 && Param.modle8[i][2] == 2) {
								for (int j = 3; j < 8; j++) {
									exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle8[i][j] - 3));
								}
								double costValue = cost(exghaustiveRouteNodeList, vehicle);
								if (costValue < minCostDelta) {
									minCostDelta = costValue;
									bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
									bestInsertVehicleId = vehicleId;
									isExhaustive = true;
								}
								exghaustiveRouteNodeList
										.subList(exghaustiveRouteNodeList.size() - 5, exghaustiveRouteNodeList.size())
										.clear();
							}
						} else {
							for (int j = 0; j < 8; j++) {
								exghaustiveRouteNodeList.add(modleNodeList.get(Param.modle8[i][j]));
							}
							double costValue = cost(exghaustiveRouteNodeList, vehicle);
							if (costValue < minCostDelta) {
								minCostDelta = costValue;
								bestNodeList = new ArrayList<>(exghaustiveRouteNodeList);
								bestInsertVehicleId = vehicleId;
								isExhaustive = true;
							}
							exghaustiveRouteNodeList
									.subList(exghaustiveRouteNodeList.size() - 8, exghaustiveRouteNodeList.size())
									.clear();
						}
					}
				}
			} else {
				for (int i = insertPos; i <= nodeListSize; i++) {
					ArrayList<Node> tempRouteNodeList;
					if (null != routeNodeList) {
						tempRouteNodeList = new ArrayList<Node>(routeNodeList);
					} else {
						tempRouteNodeList = new ArrayList<Node>();
					}
					tempRouteNodeList.add(i, newOrderPickupNode);
					for (int j = i + 1; j <= nodeListSize + 1; j++) {
						if (j != i + 1 && tempRouteNodeList.get(j - 1).getPickupItemList() != null
								&& !tempRouteNodeList.get(j - 1).getPickupItemList().isEmpty()) {
							for (int k = j; k <= nodeListSize + 1; k++) {
								if (tempRouteNodeList.get(k).getDeliveryItemList() != null
										&& !tempRouteNodeList.get(k).getDeliveryItemList().isEmpty()) {
									int len = tempRouteNodeList.get(k).getDeliveryItemList().size();
									if (tempRouteNodeList.get(j - 1).getPickupItemList().get(0).getId().equals(
											tempRouteNodeList.get(k).getDeliveryItemList().get(len - 1).getId())) {
										j = k + 1;
										break;
									}
								}
							}
						} else if (tempRouteNodeList.get(j - 1).getDeliveryItemList() != null
								&& !tempRouteNodeList.get(j - 1).getDeliveryItemList().isEmpty()) {
							boolean isTerminal = true;
							for (int k = j - 2; k >= 0; k--) {
								if (tempRouteNodeList.get(k).getPickupItemList() != null
										&& !tempRouteNodeList.get(k).getPickupItemList().isEmpty()) {
									int len = tempRouteNodeList.get(j - 1).getDeliveryItemList().size();
									if (tempRouteNodeList.get(j - 1).getDeliveryItemList().get(len - 1).getId()
											.equals(tempRouteNodeList.get(k).getPickupItemList().get(0).getId())) {
										if (k < i) {
											isTerminal = true;
											break;
										} else if (k > i) {
											isTerminal = false;
											break;
										}
									}
								}
							}
							if (isTerminal) {
								break;
							}
						}
						tempRouteNodeList.add(j, newOrderDeliveryNode);
						double costValue = cost(tempRouteNodeList, vehicle);
						if (costValue < minCostDelta) {
							minCostDelta = costValue;
							bestInsertPosI = i;
							bestInsertPosJ = j;
							bestInsertVehicleId = vehicleId;
							isExhaustive = false;
						}
						tempRouteNodeList.remove(j);
					}
				}
			}

		}

	}

	/**
	 * 返回订单CI最低代价插入车辆的全局变量：车辆编号bestInsertVehicleId、
	 * 取、送货节点插入位置bestInsertPosI、bestInsertPosJ,和最小插入适应值增量minCostDelta
	 * 
	 * @param nodeList 待插入的取送货两个节点
	 */
	private static void dispatchNewOrdersWithCI(ArrayList<Node> nodeList) {
		Node newOrderPickupNode = nodeList.get(0);
		Node newOrderDeliveryNode = nodeList.get(1);
		ArrayList<Node> routeNodeList;
		minCostDelta = Double.MAX_VALUE;
		int index = 0;
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = "V_" + String.valueOf(++index);
			Vehicle vehicle = id2VehicleItem.getValue();
			routeNodeList = vehicleId2PlannedRoute.get(vehicleId);
			double costValue0 = routeNodeList == null ? 0 : cost(routeNodeList, vehicle);
			int nodeListSize = 0;
			if (routeNodeList != null) {
				nodeListSize = routeNodeList.size();
			}
			int insertPos = 0;
			if (vehicle.getDes() != null && !newOrderPickupNode.getId().equals(vehicle.getDes().getId())) {
				insertPos = 1;
			}
			for (int i = insertPos; i <= nodeListSize; i++) {
				ArrayList<Node> tempRouteNodeList;
				if (null != routeNodeList) {
					tempRouteNodeList = new ArrayList<Node>(routeNodeList);
				} else {
					tempRouteNodeList = new ArrayList<Node>();
				}
				tempRouteNodeList.add(i, newOrderPickupNode);
				for (int j = i + 1; j <= nodeListSize + 1; j++) {
					tempRouteNodeList.add(j, newOrderDeliveryNode);
					double costValue = cost(tempRouteNodeList, vehicle);
					if (costValue - costValue0 < minCostDelta) {
						minCostDelta = costValue - costValue0;
						bestInsertPosI = i;
						bestInsertPosJ = j;
						bestInsertVehicleId = vehicleId;
					}
					tempRouteNodeList.remove(j);
				}
			}
		}

	}


	/**
	 * 加入Dock约束的cost函数
	 * 
	 * @param tempRouteNodeList
	 * @param vehicle
	 * @return
	 */
	private static double cost(ArrayList<Node> tempRouteNodeList, Vehicle vehicle) {
		String curFactoryId = vehicle.getCurFactoryId();
		double drivingDistance = 0;
		double overTimeSum = 0;
		double objF;
		double capacity = vehicle.getBoardCapacity();
		ArrayList<OrderItem> carryItems = new ArrayList<OrderItem>();
		if (vehicle.getDes() != null) {
			carryItems = (ArrayList<OrderItem>) vehicle.getCarryingItems();
		}
		if (tempRouteNodeList != null && !isFeasible(tempRouteNodeList, carryItems, capacity)) {
			return Double.MAX_VALUE;
		}
		HashMap dockTable = new HashMap<String, ArrayList<Integer[]>>();
		int n = 0;
		int vehicleNum = id2VehicleMap.size();
		int[] currNode = new int[vehicleNum];
		int[] currTime = new int[vehicleNum];
		int[] leaveLastNodeTime = new int[vehicleNum];
		int updateTime = id2VehicleMap.get("V_1").getGpsUpdateTime();
		int[] nNode = new int[vehicleNum];
		int index = 0;
		for (Map.Entry<String, Vehicle> id2Vehicle : id2VehicleMap.entrySet()) {
			String vehicleId = id2Vehicle.getKey();
			Vehicle otherVehicle = id2Vehicle.getValue();
			double distance = 0;
			int time = 0;
			if (otherVehicle.getCurFactoryId() != null && otherVehicle.getCurFactoryId().length() > 0) {
				if (otherVehicle.getLeaveTimeAtCurrentFactory() > otherVehicle.getGpsUpdateTime()) {
					Integer[] tw = new Integer[] { otherVehicle.getArriveTimeAtCurrentFactory(),
							otherVehicle.getLeaveTimeAtCurrentFactory() };
					ArrayList<Integer[]> twList = (ArrayList<Integer[]>) dockTable.get(otherVehicle.getCurFactoryId());
					if (null == twList) {
						twList = new ArrayList<>();
					}
					twList.add(tw);
					dockTable.put(otherVehicle.getCurFactoryId(), twList);
				}
				leaveLastNodeTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory();
			} else {
				leaveLastNodeTime[index] = otherVehicle.getGpsUpdateTime();
			}
			if (vehicleId.equals(vehicle.getId())) {
				if (tempRouteNodeList == null || tempRouteNodeList.size() == 0) {
					currNode[index] = Integer.MAX_VALUE;
					currTime[index] = Integer.MAX_VALUE;
					nNode[index] = 0;
				} else {
					currNode[index] = 0;
					nNode[index] = tempRouteNodeList.size();
					if (vehicle.getDes() == null) {
						if (vehicle.getCurFactoryId() == "") {
							System.err.println("cur factory have no value");
						}
						if (tempRouteNodeList.size() == 0) {
							System.err.println("tempRouteNodeList have no length");
						}

						if (vehicle.getCurFactoryId().equals(tempRouteNodeList.get(0).getId())) {
							currTime[index] = vehicle.getLeaveTimeAtCurrentFactory();
						} else {
							String disAndTimeStr = id2RouteMap
									.get(vehicle.getCurFactoryId() + "+" + tempRouteNodeList.get(0).getId());
							String[] disAndTime = disAndTimeStr.split("\\+");
							distance = Double.parseDouble(disAndTime[0]);
							time = Integer.parseInt(disAndTime[1]);
							currTime[index] = vehicle.getLeaveTimeAtCurrentFactory() + time;
							drivingDistance = drivingDistance + distance;
						}
					} else {
						if (curFactoryId != null && curFactoryId.length() > 0) {
							if (!curFactoryId.equals(tempRouteNodeList.get(0).getId())) {
								currTime[index] = vehicle.getLeaveTimeAtCurrentFactory();
								String disAndTimeStr = id2RouteMap
										.get(curFactoryId + "+" + tempRouteNodeList.get(0).getId());
								String[] disAndTime = disAndTimeStr.split("\\+");
								distance = Double.parseDouble(disAndTime[0]);
								time = Integer.parseInt(disAndTime[1]);
								drivingDistance = drivingDistance + distance;
								currTime[index] += time;
							} else {
								currTime[index] = vehicle.getLeaveTimeAtCurrentFactory();
							}
						} else {
							currTime[index] = vehicle.getDes().getArriveTime();
						}
					}

					n = n + 1;
				}
			} else if (vehicleId2PlannedRoute.get(vehicleId) != null
					&& vehicleId2PlannedRoute.get(vehicleId).size() > 0) {
				currNode[index] = 0;
				nNode[index] = vehicleId2PlannedRoute.get(vehicleId).size();
				if (otherVehicle.getDes() == null) {
					if (otherVehicle.getCurFactoryId().equals(vehicleId2PlannedRoute.get(vehicleId).get(0).getId())) {
						currTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory();
					} else {
						String disAndTimeStr = id2RouteMap.get(otherVehicle.getCurFactoryId() + "+"
								+ vehicleId2PlannedRoute.get(vehicleId).get(0).getId());
						if (disAndTimeStr == null)
							System.err.println("no distance");
						String[] disAndTime = disAndTimeStr.split("\\+");
						distance = Double.parseDouble(disAndTime[0]);
						time = Integer.parseInt(disAndTime[1]);
						currTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory() + time;
						drivingDistance = drivingDistance + distance;
					}
				} else {
					if (otherVehicle.getCurFactoryId() != null && otherVehicle.getCurFactoryId().length() > 0) {
						if (!otherVehicle.getCurFactoryId()
								.equals(vehicleId2PlannedRoute.get(vehicleId).get(0).getId())) {
							currTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory();
							String disAndTimeStr = id2RouteMap.get(otherVehicle.getCurFactoryId() + "+"
									+ vehicleId2PlannedRoute.get(vehicleId).get(0).getId());
							String[] disAndTime = disAndTimeStr.split("\\+");
							distance = Double.parseDouble(disAndTime[0]);
							time = Integer.parseInt(disAndTime[1]);
							currTime[index] += time;
							drivingDistance = drivingDistance + distance;
						} else {
							currTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory();

						}
					} else {
						currTime[index] = otherVehicle.getDes().getArriveTime();
					}
				}

				n = n + 1;
			} else {
				currNode[index] = Integer.MAX_VALUE;
				currTime[index] = Integer.MAX_VALUE;
				nNode[index] = 0;
			}
			index++;
		}

		boolean flag = false;
		while (n > 0) {
			int minT = Integer.MAX_VALUE, minT2VehicleIndex = 0;
			int tTrue = minT, idx = 0;
			for (int i = 0; i < vehicleNum; i++) {
				if (currTime[i] < minT) {
					minT = currTime[i];
					minT2VehicleIndex = i;
				}
			}
			String minT2VehicleId = "V_" + String.valueOf(minT2VehicleIndex + 1);

			ArrayList<Node> minTNodeList;
			if (minT2VehicleId.equals(vehicle.getId())) {
				minTNodeList = tempRouteNodeList;
			} else {
				minTNodeList = vehicleId2PlannedRoute.get(minT2VehicleId);
			}
			Node minTNode = minTNodeList.get(currNode[minT2VehicleIndex]);
			if (minTNode.getDeliveryItemList() != null && minTNode.getDeliveryItemList().size() > 0) {
				String beforeOrderId = "", nextOrderId = "";
				for (OrderItem orderItem : minTNode.getDeliveryItemList()) {
					nextOrderId = orderItem.getOrderId();
					if (!beforeOrderId.equals(nextOrderId)) {
						int commitCompleteTime = orderItem.getCommittedCompletionTime();
						overTimeSum += Math.max(0, currTime[minT2VehicleIndex] - commitCompleteTime);
					}
					beforeOrderId = nextOrderId;
				}
			}
			/* 遍历 dockTable */
			ArrayList<Integer> usedEndTime = new ArrayList<>();
			ArrayList<Integer[]> timeSlots = (ArrayList<Integer[]>) dockTable.get(minTNode.getId());
			if (timeSlots != null) {
				for (int i = 0; i < timeSlots.size(); i++) {
					Integer[] timeSlot = timeSlots.get(i);
					if (timeSlot[1] <= minT) {
						timeSlots.remove(i);/* 这个 timeslot 在 minT 时刻没有占用 dock，并且在后面的时刻也不会占用 dock */
						i--;
					} else if (timeSlot[0] <= minT && minT < timeSlot[1]) {
						usedEndTime.add(timeSlot[1]);/* 记录这个 timeslot 占用 dock 时段的最后时间点 */
					} else {
						System.err.println("------------ timeslot.start>minT--------------");
					}
				}
			}
			if (usedEndTime.size() < 6) {
				tTrue = minT;
			} else {
				flag = true;
				idx = usedEndTime.size() - 6;
				usedEndTime.sort(new Comparator<Integer>() {
					public int compare(Integer a, Integer b) {
						if (a < b)
							return -1;
						else if (a == b)
							return 0;
						else
							return 1;
					}
				});
				tTrue = usedEndTime.get(idx);
			}

			boolean isSameAddress = false;
			int serviceTime = minTNodeList.get(currNode[minT2VehicleIndex]).getServiceTime();
			String curFatoryId = minTNodeList.get(currNode[minT2VehicleIndex]).getId();
			currNode[minT2VehicleIndex]++;
			while (currNode[minT2VehicleIndex] < nNode[minT2VehicleIndex]
					&& curFatoryId.equals(minTNodeList.get(currNode[minT2VehicleIndex]).getId())) {
				if (minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList() != null
						&& minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList().size() > 0) {
					String beforeOrderId = "", nextOrderId = "";
					for (OrderItem orderItem : minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList()) {
						nextOrderId = orderItem.getOrderId();
						if (!beforeOrderId.equals(nextOrderId)) {
							int commitCompleteTime = orderItem.getCommittedCompletionTime();
							overTimeSum += Math.max(0, currTime[minT2VehicleIndex] - commitCompleteTime);
						}
						beforeOrderId = nextOrderId;
					}
				}
				isSameAddress = true;
				serviceTime += minTNodeList.get(currNode[minT2VehicleIndex]).getServiceTime();
				currNode[minT2VehicleIndex]++;
			}

			if (currNode[minT2VehicleIndex] >= nNode[minT2VehicleIndex]) {
				n = n - 1;
				currNode[minT2VehicleIndex] = Integer.MAX_VALUE;
				currTime[minT2VehicleIndex] = Integer.MAX_VALUE;
				nNode[minT2VehicleIndex] = 0;
			} else {
				String disAndTimeStr = id2RouteMap
						.get(curFatoryId + "+" + minTNodeList.get(currNode[minT2VehicleIndex]).getId());
				String[] disAndTime = disAndTimeStr.split("\\+");
				double distance = Double.parseDouble(disAndTime[0]);
				int time = Integer.parseInt(disAndTime[1]);
				currTime[minT2VehicleIndex] = tTrue + APPROACHING_DOCK_TIME + serviceTime + time;
				leaveLastNodeTime[minT2VehicleIndex] = tTrue + APPROACHING_DOCK_TIME + serviceTime;
				drivingDistance += distance;
			}

			Integer[] tw = new Integer[] { minT, tTrue + APPROACHING_DOCK_TIME + serviceTime };
			ArrayList<Integer[]> twList = (ArrayList<Integer[]>) dockTable.get(minTNode.getId());
			if (null == twList) {
				twList = new ArrayList<>();
			}
			twList.add(tw);
			dockTable.put(minTNode.getId(), twList);

		}
		objF = Delta * overTimeSum + drivingDistance / (double) id2VehicleMap.size();
		if (objF < 0) {
			System.err.println("the objective function less than 0.");
		}
		return objF;
	}

	private static double cost() {
		double drivingDistance = 0;
		double overTimeSum = 0;
		double objF;
		HashMap dockTable = new HashMap<String, ArrayList<Integer[]>>();
		int n = 0;
		int vehicleNum = id2VehicleMap.size();
		int[] currNode = new int[vehicleNum];
		int[] currTime = new int[vehicleNum];
		int[] leaveLastNodeTime = new int[vehicleNum];
		int updateTime = id2VehicleMap.get("V_1").getGpsUpdateTime();
		int[] nNode = new int[vehicleNum];
		int index = 0;
		for (Map.Entry<String, Vehicle> id2Vehicle : id2VehicleMap.entrySet()) {
			String vehicleId = id2Vehicle.getKey();
			Vehicle otherVehicle = id2Vehicle.getValue();
			double distance = 0;
			int time = 0;
			if (otherVehicle.getCurFactoryId() != null && otherVehicle.getCurFactoryId().length() > 0) {
				if (otherVehicle.getLeaveTimeAtCurrentFactory() > otherVehicle.getGpsUpdateTime()) {
					Integer[] tw = new Integer[] { otherVehicle.getArriveTimeAtCurrentFactory(),
							otherVehicle.getLeaveTimeAtCurrentFactory() };
					ArrayList<Integer[]> twList = (ArrayList<Integer[]>) dockTable.get(otherVehicle.getCurFactoryId());
					if (null == twList) {
						twList = new ArrayList<>();
					}
					twList.add(tw);
					dockTable.put(otherVehicle.getCurFactoryId(), twList);
				}
				leaveLastNodeTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory();
			} else {
				leaveLastNodeTime[index] = otherVehicle.getGpsUpdateTime();
			}
			if (vehicleId2PlannedRoute.get(vehicleId) != null && vehicleId2PlannedRoute.get(vehicleId).size() > 0) {
				currNode[index] = 0;
				nNode[index] = vehicleId2PlannedRoute.get(vehicleId).size();
				if (otherVehicle.getDes() == null) {
					if (otherVehicle.getCurFactoryId().equals(vehicleId2PlannedRoute.get(vehicleId).get(0).getId())) {
						currTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory();
					} else {
						String disAndTimeStr = id2RouteMap.get(otherVehicle.getCurFactoryId() + "+"
								+ vehicleId2PlannedRoute.get(vehicleId).get(0).getId());
						if (disAndTimeStr == null)
							System.err.println("no distance");
						String[] disAndTime = disAndTimeStr.split("\\+");
						distance = Double.parseDouble(disAndTime[0]);
						time = Integer.parseInt(disAndTime[1]);
						currTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory() + time;
						drivingDistance = drivingDistance + distance;
					}
				} else {
					if (otherVehicle.getCurFactoryId() != null && otherVehicle.getCurFactoryId().length() > 0) {
						if (!otherVehicle.getCurFactoryId()
								.equals(vehicleId2PlannedRoute.get(vehicleId).get(0).getId())) {
							currTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory();
							String disAndTimeStr = id2RouteMap.get(otherVehicle.getCurFactoryId() + "+"
									+ vehicleId2PlannedRoute.get(vehicleId).get(0).getId());
							String[] disAndTime = disAndTimeStr.split("\\+");
							distance = Double.parseDouble(disAndTime[0]);
							time = Integer.parseInt(disAndTime[1]);
							currTime[index] += time;
							drivingDistance = drivingDistance + distance;
						} else {
							currTime[index] = otherVehicle.getLeaveTimeAtCurrentFactory();

						}

					} else {
						currTime[index] = otherVehicle.getDes().getArriveTime();
					}
				}

				n = n + 1;
			} else {
				currNode[index] = Integer.MAX_VALUE;
				currTime[index] = Integer.MAX_VALUE;
				nNode[index] = 0;
			}
			index++;
		}
		boolean flag = false;
		while (n > 0) {
			int minT = Integer.MAX_VALUE, minT2VehicleIndex = 0;
			int tTrue = minT, idx = 0;
			for (int i = 0; i < vehicleNum; i++) {
				if (currTime[i] < minT) {
					minT = currTime[i];
					minT2VehicleIndex = i;
				}
			}
			String minT2VehicleId = "V_" + String.valueOf(minT2VehicleIndex + 1);

			ArrayList<Node> minTNodeList;
			minTNodeList = vehicleId2PlannedRoute.get(minT2VehicleId);
			Node minTNode = minTNodeList.get(currNode[minT2VehicleIndex]);
			if (minTNode.getDeliveryItemList() != null && minTNode.getDeliveryItemList().size() > 0) {
				String beforeOrderId = "", nextOrderId = "";
				for (OrderItem orderItem : minTNode.getDeliveryItemList()) {
					nextOrderId = orderItem.getOrderId();
					if (!beforeOrderId.equals(nextOrderId)) {
						int commitCompleteTime = orderItem.getCommittedCompletionTime();
						overTimeSum += Math.max(0, currTime[minT2VehicleIndex] - commitCompleteTime);
					}
					beforeOrderId = nextOrderId;
				}
			}
			/* 遍历 dockTable */
			ArrayList<Integer> usedEndTime = new ArrayList<>();
			ArrayList<Integer[]> timeSlots = (ArrayList<Integer[]>) dockTable.get(minTNode.getId());
			if (timeSlots != null) {
				for (int i = 0; i < timeSlots.size(); i++) {
					Integer[] timeSlot = timeSlots.get(i);
					if (timeSlot[1] <= minT) {
						timeSlots.remove(i);/* 这个 timeslot 在 minT 时刻没有占用 dock，并且在后面的时刻也不会占用 dock */
						i--;
					} else if (timeSlot[0] <= minT && minT < timeSlot[1]) {
						usedEndTime.add(timeSlot[1]);/* 记录这个 timeslot 占用 dock 时段的最后时间点 */
					} else {
						System.err.println("------------ timeslot.start>minT--------------");
					}
				}
			}
			if (usedEndTime.size() < 6) {
				tTrue = minT;
			} else {
				flag = true;
				idx = usedEndTime.size() - 6;
				usedEndTime.sort(new Comparator<Integer>() {
					public int compare(Integer a, Integer b) {
						if (a < b)
							return -1;
						else
							return 1;
					}
				});
				tTrue = usedEndTime.get(idx);
			}

			boolean isSameAddress = false;
			int serviceTime = minTNodeList.get(currNode[minT2VehicleIndex]).getServiceTime();
			String curFatoryId = minTNodeList.get(currNode[minT2VehicleIndex]).getId();
			currNode[minT2VehicleIndex]++;
			while (currNode[minT2VehicleIndex] < nNode[minT2VehicleIndex]
					&& curFatoryId.equals(minTNodeList.get(currNode[minT2VehicleIndex]).getId())) {
				if (minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList() != null
						&& minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList().size() > 0) {
					String beforeOrderId = "", nextOrderId = "";
					for (OrderItem orderItem : minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList()) {
						nextOrderId = orderItem.getOrderId();
						if (!beforeOrderId.equals(nextOrderId)) {
							int commitCompleteTime = orderItem.getCommittedCompletionTime();
							overTimeSum += Math.max(0, currTime[minT2VehicleIndex] - commitCompleteTime);
						}
						beforeOrderId = nextOrderId;
					}
				}
				isSameAddress = true;
				serviceTime += minTNodeList.get(currNode[minT2VehicleIndex]).getServiceTime();
				currNode[minT2VehicleIndex]++;
			}

			if (currNode[minT2VehicleIndex] >= nNode[minT2VehicleIndex]) {
				n = n - 1;
				currNode[minT2VehicleIndex] = Integer.MAX_VALUE;
				currTime[minT2VehicleIndex] = Integer.MAX_VALUE;
				nNode[minT2VehicleIndex] = 0;
			} else {
				String disAndTimeStr = id2RouteMap
						.get(curFatoryId + "+" + minTNodeList.get(currNode[minT2VehicleIndex]).getId());
				String[] disAndTime = disAndTimeStr.split("\\+");
				double distance = Double.parseDouble(disAndTime[0]);
				int time = Integer.parseInt(disAndTime[1]);
				currTime[minT2VehicleIndex] = tTrue + APPROACHING_DOCK_TIME + serviceTime + time;
				leaveLastNodeTime[minT2VehicleIndex] = tTrue + APPROACHING_DOCK_TIME + serviceTime;
				drivingDistance += distance;
			}

			Integer[] tw = new Integer[] { minT, tTrue + APPROACHING_DOCK_TIME + serviceTime };
			ArrayList<Integer[]> twList = (ArrayList<Integer[]>) dockTable.get(minTNode.getId());
			if (null == twList) {
				twList = new ArrayList<>();
			}
			twList.add(tw);
			dockTable.put(minTNode.getId(), twList);

		}
		objF = Delta * overTimeSum + drivingDistance / (double) id2VehicleMap.size();
		if (objF < 0) {
			System.err.println("the objective function less than 0.");
		}
		return objF;
	}



	/**
	 * 检查分配的路径节点是否超过车的capacity
	 * 
	 * @param routeNodeList 车的调度路径
	 * @param carryingItems 车上已装载的物料
	 *                      vehicle.carryingItems，即车上的货物，为取货顺序，但用vehicle.des.deliveryItems更好
	 * @param capacity      车的容量
	 * @param pos
	 */
	private static boolean isFeasible(ArrayList<Node> routeNodeList, ArrayList<OrderItem> carryingItems,
			double capacity) {
		ArrayList<OrderItem> unloadItemList = new ArrayList<OrderItem>();
		if (carryingItems != null && carryingItems.size() > 0) {

			for (int i = carryingItems.size() - 1; i >= 0; i--) {
				unloadItemList.add(carryingItems.get(i));
			}
		}
		for (Node node : routeNodeList) {
			ArrayList<OrderItem> deliveryItems = (ArrayList<OrderItem>) node.getDeliveryItemList();
			ArrayList<OrderItem> pickupItems = (ArrayList<OrderItem>) node.getPickupItemList();
			if (deliveryItems != null && deliveryItems.size() > 0) {
				for (OrderItem orderItem : deliveryItems) {
					if (unloadItemList.isEmpty() || unloadItemList.get(0) == null
							|| !unloadItemList.get(0).getId().equals(orderItem.getId())) {
						System.err.println("Violate FILO");
						return false;
					}
					unloadItemList.remove(0);
				}
			}
			if (pickupItems != null && pickupItems.size() > 0) {
				for (OrderItem orderItem : pickupItems) {
					unloadItemList.add(0, orderItem);
				}
			}
		}
		if (!unloadItemList.isEmpty()) {
			System.err.println("Violate FILO");
			return false;
		}
		double leftCapacity = capacity;
		if (carryingItems != null) {
			for (OrderItem orderitem : carryingItems) {
				leftCapacity -= orderitem.getDemand();
			}
		}
		for (int i = 0; i < routeNodeList.size(); i++) {
			Node node = routeNodeList.get(i);
			ArrayList<OrderItem> deliveryItems = (ArrayList<OrderItem>) node.getDeliveryItemList();
			ArrayList<OrderItem> pickupItems = (ArrayList<OrderItem>) node.getPickupItemList();
			if (null != deliveryItems) {
				for (OrderItem orderItem : deliveryItems) {
					leftCapacity += orderItem.getDemand();
					if (leftCapacity > capacity) {
						return false;
					}
				}
			}
			if (null != pickupItems) {
				for (OrderItem orderItem : pickupItems) {
					leftCapacity -= orderItem.getDemand();
					if (leftCapacity < 0) {
						return false;
					}
				}
			}

		}

		return unloadItemList.isEmpty();
	}

	/**
	 * 对带合并节点路径的旧解和当前输入，得到新订单；已完成订单；恢复前一解;将已完成订单从前一解中删除
	 */
	private static void restoreSceneWithMergeNode() {
		String orderItemsExelPath = "./algorithm/memory/orderItemsState.xls";
		completeOrderItems = "";
		newOrderItems = "";
		onVehicleOrderItems = "";
		unallocatedOrderItems = "";
		routeBefore = "";
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			vehicleId2PlannedRoute.put(vehicleId, null);
		}
		for (Map.Entry<String, OrderItem> onVehicleOrderItem : id2OngoingOrderMap.entrySet()) {
			onVehicleOrderItems += onVehicleOrderItem.getKey() + " ";
		}
		onVehicleOrderItems = onVehicleOrderItems.trim();
		for (Map.Entry<String, OrderItem> unallocatedOrderItem : id2UnallocatedOrderMap.entrySet()) {
			unallocatedOrderItems += unallocatedOrderItem.getKey() + " ";
		}
		unallocatedOrderItems = unallocatedOrderItems.trim();
		File f = new File(orderItemsExelPath);
		if (!f.exists()) {
			newOrderItems = unallocatedOrderItems;
			completeOrderItems = "";
			routeBefore = "";
		} else {
			FileInputStream fis = null;
			HSSFWorkbook wb = null;
			try {
				fis = new FileInputStream(f);
				wb = new HSSFWorkbook(fis);
				HSSFSheet sheet = wb.getSheet("orde_items_state");
				List<String> list = new ArrayList<String>();
				HSSFRow lastRow = sheet.getRow(sheet.getLastRowNum());
				HSSFCell lastOnVehicleCell = lastRow.getCell(3);
				String[] lastonVehicleItems = lastOnVehicleCell.getStringCellValue().split(" ");
				String[] curOnVehicleItems = onVehicleOrderItems.split(" ");
				list = Arrays.asList(curOnVehicleItems);
				for (String lastonVehicleItem : lastonVehicleItems) {
					if (!list.contains(lastonVehicleItem)) {
						completeOrderItems += lastonVehicleItem + " ";
					}
				}
				completeOrderItems = completeOrderItems.trim();
				lastRow = sheet.getRow(sheet.getLastRowNum());
				HSSFCell lastUnallocatedItemsCell = lastRow.getCell(6);
				String[] lastUnallocatedItems = lastUnallocatedItemsCell.getStringCellValue().split(" ");
				String[] curUnallocatedItems = unallocatedOrderItems.split(" ");
				list = Arrays.asList(lastUnallocatedItems);
				for (String curUnallocatedItem : curUnallocatedItems) {
					if (!list.contains(curUnallocatedItem)) {
						newOrderItems += curUnallocatedItem + " ";
					}
				}
				newOrderItems = newOrderItems.trim();
				HSSFCell lastRouteAfterCell = lastRow.getCell(10);
				routeBefore = lastRouteAfterCell.getStringCellValue();
				if (null != fis)
					fis.close();
				String[] routeBeforeSplit = routeBefore.split("V");
				String[] completeItemsArray = completeOrderItems.split(" ");
				for (int i = 1; i < routeBeforeSplit.length; i++) {
					routeBeforeSplit[i] = routeBeforeSplit[i].trim();
					int strLen = routeBeforeSplit[i].split(":")[1].length();
					String numStr = routeBeforeSplit[i].split(":")[0];
					String vehicleId = "V_" + numStr.substring(1, numStr.length());
					if (strLen < 3) {
						vehicleId2PlannedRoute.put(vehicleId, null);
						continue;
					}
					String routeNodesStr = routeBeforeSplit[i].split(":")[1];
					String[] routeNodes = routeNodesStr.substring(1, routeNodesStr.length() - 1).split(" ");
					List<String> nodeList = new ArrayList(Arrays.asList(routeNodes));
					String[] deliveryAndPickupItemsStr = nodeList.get(0).split("&");
					String deliveryItemsId = "";
					String pickupItemsId = "";
					String[] dItemIdArrayTmp;
					String[] pItemIdArrayTmp;
					String[] dItemIdArray = null;
					String[] pItemIdArray = null;
					String[] dOrderItemNum = null, pOrderItemNum = null;
					if (deliveryAndPickupItemsStr.length == 2) {
						deliveryItemsId = deliveryAndPickupItemsStr[0];
						pickupItemsId = deliveryAndPickupItemsStr[1];
						dItemIdArrayTmp = deliveryItemsId.split("d");
						dItemIdArray = new String[dItemIdArrayTmp.length - 1];
						dOrderItemNum = new String[dItemIdArrayTmp.length - 1];
						pItemIdArrayTmp = pickupItemsId.split("p");
						pItemIdArray = new String[pItemIdArrayTmp.length - 1];
						pOrderItemNum = new String[pItemIdArrayTmp.length - 1];
						for (int j = 1; j < dItemIdArrayTmp.length; j++) {
							dOrderItemNum[j - 1] = dItemIdArrayTmp[j].split("_")[0];
							dItemIdArray[j - 1] = dItemIdArrayTmp[j].split("_")[1];
						}
						for (int j = 1; j < pItemIdArrayTmp.length; j++) {
							pOrderItemNum[j - 1] = pItemIdArrayTmp[j].split("_")[0];
							pItemIdArray[j - 1] = pItemIdArrayTmp[j].split("_")[1];
						}
					} else {
						if (deliveryAndPickupItemsStr[0].charAt(0) == 'd') {
							deliveryItemsId = deliveryAndPickupItemsStr[0];
							dItemIdArrayTmp = deliveryItemsId.split("d");
							dItemIdArray = new String[dItemIdArrayTmp.length - 1];
							dOrderItemNum = new String[dItemIdArrayTmp.length - 1];
							for (int j = 1; j < dItemIdArrayTmp.length; j++) {
								dOrderItemNum[j - 1] = dItemIdArrayTmp[j].split("_")[0];
								dItemIdArray[j - 1] = dItemIdArrayTmp[j].split("_")[1];
							}
						} else {
							pickupItemsId = deliveryAndPickupItemsStr[0];
							pItemIdArrayTmp = pickupItemsId.split("p");
							pItemIdArray = new String[pItemIdArrayTmp.length - 1];
							pOrderItemNum = new String[pItemIdArrayTmp.length - 1];
							for (int j = 1; j < pItemIdArrayTmp.length; j++) {
								pOrderItemNum[j - 1] = pItemIdArrayTmp[j].split("_")[0];
								pItemIdArray[j - 1] = pItemIdArrayTmp[j].split("_")[1];
							}
						}
					}
					String deliveryOrderItemsStr = "", pickupOrderItemsStr = "", resultOrderItemsStr = "";
					int dNum = 0, pNum = 0;
					if (deliveryItemsId.length() != 0) {
						for (int j = 0; j < dItemIdArray.length; j++) {
							for (int k = 0; k < completeItemsArray.length; k++) {
								if (completeItemsArray[k].equals(dItemIdArray[j])) {
									dNum++;
									break;
								}
							}
							if (dNum - 1 != j) {
								break;
							}
						}
						if (dNum != dItemIdArray.length) {
							for (int j = dNum; j < dItemIdArray.length; j++) {
								deliveryOrderItemsStr += "d" + dOrderItemNum[j] + "_" + dItemIdArray[j];
							}
						}
						if (dNum == dItemIdArray.length && pickupItemsId.length() != 0) {
							for (int j = 0; j < pItemIdArray.length; j++) {
								for (int k = 0; k < curOnVehicleItems.length; k++) {
									if (curOnVehicleItems[k].equals(pItemIdArray[j])) {
										pNum++;
										break;
									}
								}
								if (pNum - 1 != j) {
									break;
								}
							}
						}
						if (pItemIdArray != null && pNum != pItemIdArray.length) {
							for (int j = pNum; j < pItemIdArray.length; j++) {
								pickupOrderItemsStr += "p" + pOrderItemNum[j] + "_" + pItemIdArray[j];
							}
						}
					} else if (pickupItemsId.length() != 0) {
						for (int j = 0; j < pItemIdArray.length; j++) {
							for (int k = 0; k < curOnVehicleItems.length; k++) {
								if (curOnVehicleItems[k].equals(pItemIdArray[j])) {
									pNum++;
									break;
								}
							}
							if (pNum - 1 != j) {
								break;
							}
						}
						if (pNum != pItemIdArray.length) {
							for (int j = pNum; j < pItemIdArray.length; j++) {
								pickupOrderItemsStr += "p" + pOrderItemNum[j] + "_" + pItemIdArray[j];
							}
						}
					}
					if (deliveryOrderItemsStr.length() == 0 && pickupOrderItemsStr.length() == 0) {
						nodeList.remove(0);
					} else {
						if (deliveryOrderItemsStr.length() != 0) {
							resultOrderItemsStr += deliveryOrderItemsStr;
							if (pickupOrderItemsStr.length() != 0) {
								resultOrderItemsStr += "&" + pickupOrderItemsStr;
							}
						} else if (pickupOrderItemsStr.length() != 0) {
							resultOrderItemsStr += pickupOrderItemsStr;
						}
						nodeList.set(0, resultOrderItemsStr);
					} 

					if (nodeList.size() > 0) {
						ArrayList<Node> planRoute = new ArrayList<Node>();
						for (int j = 0; j < nodeList.size(); j++) {
							List<OrderItem> deliveryItemList = new ArrayList<OrderItem>();
							List<OrderItem> pickupItemList = new ArrayList<OrderItem>();
							OrderItem deliveryOrPickupItem = null, pickupOrderItem = null;
							boolean isBothOp = false;
							char op1;
							deliveryAndPickupItemsStr = nodeList.get(j).split("&");
							if (deliveryAndPickupItemsStr[0].charAt(0) == 'd') {
								op1 = 'd';
							} else {
								op1 = 'p';
							}
							if (deliveryAndPickupItemsStr.length == 2) {
								isBothOp = true;
								deliveryItemsId = deliveryAndPickupItemsStr[0];
								pickupItemsId = deliveryAndPickupItemsStr[1];
							} else {
								if (op1 == 'd') {
									deliveryItemsId = deliveryAndPickupItemsStr[0];
								} else {
									pickupItemsId = deliveryAndPickupItemsStr[0];
								}
							}
							int[] dIdEndNumber;
							int[] pIdEndNumber;
							int[] dItemNum;
							int[] pItemNum;
							if (!isBothOp) {
								if (op1 == 'd') {
									dItemIdArrayTmp = deliveryItemsId.split("d");
									dItemIdArray = new String[dItemIdArrayTmp.length - 1];
									dItemNum = new int[dItemIdArrayTmp.length - 1];
									dIdEndNumber = new int[dItemIdArrayTmp.length - 1];
									for (int k = 1; k < dItemIdArrayTmp.length; k++) {
										dItemNum[k - 1] = Integer.valueOf(dItemIdArrayTmp[k].split("_")[0]);
										dItemIdArray[k - 1] = dItemIdArrayTmp[k].split("_")[1];
										dIdEndNumber[k - 1] = Integer.valueOf(dItemIdArray[k - 1].split("-")[1]);
									}
									for (int k = 0; k < dItemIdArray.length; k++) {
										for (int n = 0; n < dItemNum[k]; n++) {
											deliveryOrPickupItem = id2OrderItemMap.get(dItemIdArray[k]);
											deliveryItemList.add(deliveryOrPickupItem);
											dItemIdArray[k] = dItemIdArray[k].split("-")[0] + "-"
													+ String.valueOf(--dIdEndNumber[k]);
										}
									}
								} else {
									pItemIdArrayTmp = pickupItemsId.split("p");
									pItemIdArray = new String[pItemIdArrayTmp.length - 1];
									pItemNum = new int[pItemIdArrayTmp.length - 1];
									pIdEndNumber = new int[pItemIdArrayTmp.length - 1];
									for (int k = 1; k < pItemIdArrayTmp.length; k++) {
										String str = pItemIdArrayTmp[k].split("_")[0];
										pItemNum[k - 1] = Integer.valueOf(str);
										pItemIdArray[k - 1] = pItemIdArrayTmp[k].split("_")[1];
										pIdEndNumber[k - 1] = Integer.valueOf(pItemIdArray[k - 1].split("-")[1]);
									}
									for (int k = 0; k < pItemIdArray.length; k++) {
										for (int n = 0; n < pItemNum[k]; n++) {
											deliveryOrPickupItem = id2OrderItemMap.get(pItemIdArray[k]);
											pickupItemList.add(deliveryOrPickupItem);
											pItemIdArray[k] = pItemIdArray[k].split("-")[0] + "-"
													+ String.valueOf(++pIdEndNumber[k]);
										}
									}
								}

							} else {
								dItemIdArrayTmp = deliveryItemsId.split("d");
								dItemIdArray = new String[dItemIdArrayTmp.length - 1];
								dItemNum = new int[dItemIdArrayTmp.length - 1];
								dIdEndNumber = new int[dItemIdArrayTmp.length - 1];
								for (int k = 1; k < dItemIdArrayTmp.length; k++) {
									dItemNum[k - 1] = Integer.valueOf(dItemIdArrayTmp[k].split("_")[0]);
									dItemIdArray[k - 1] = dItemIdArrayTmp[k].split("_")[1];
									dIdEndNumber[k - 1] = Integer.valueOf(dItemIdArray[k - 1].split("-")[1]);
								}
								for (int k = 0; k < dItemIdArray.length; k++) {
									for (int n = 0; n < dItemNum[k]; n++) {
										deliveryOrPickupItem = id2OrderItemMap.get(dItemIdArray[k]);
										deliveryItemList.add(deliveryOrPickupItem);
										dItemIdArray[k] = dItemIdArray[k].split("-")[0] + "-"
												+ String.valueOf(--dIdEndNumber[k]);
									}
								}
								pItemIdArrayTmp = pickupItemsId.split("p");
								pItemIdArray = new String[pItemIdArrayTmp.length - 1];
								pItemNum = new int[pItemIdArrayTmp.length - 1];
								pIdEndNumber = new int[pItemIdArrayTmp.length - 1];
								for (int k = 1; k < pItemIdArrayTmp.length; k++) {
									pItemNum[k - 1] = Integer.valueOf(pItemIdArrayTmp[k].split("_")[0]);
									pItemIdArray[k - 1] = pItemIdArrayTmp[k].split("_")[1];
									pIdEndNumber[k - 1] = Integer.valueOf(pItemIdArray[k - 1].split("-")[1]);
								}
								for (int k = 0; k < pItemIdArray.length; k++) {
									for (int n = 0; n < pItemNum[k]; n++) {
										pickupOrderItem = id2OrderItemMap.get(pItemIdArray[k]);
										pickupItemList.add(pickupOrderItem);
										pItemIdArray[k] = pItemIdArray[k].split("-")[0] + "-"
												+ String.valueOf(++pIdEndNumber[k]);
									}
								}
							}
							String factoryId;
							if (op1 == 'd') {
								factoryId = deliveryOrPickupItem.getDeliveryFactoryId();
							} else {
								factoryId = deliveryOrPickupItem.getPickupFactoryId();
							}
							Factory factory = id2FactoryMap.get(factoryId);
							Node node = new Node(factoryId, deliveryItemList, pickupItemList, factory.getLng(),
									factory.getLat());
							planRoute.add(node);
						}
						if (nodeList.size() > 0) {
							vehicleId2PlannedRoute.put(vehicleId, planRoute);
						}

					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 得到新订单；已完成订单；恢复前一解;将已完成订单从前一解中删除
	 */
	private static void restoreScene() {
		String orderItemsExelPath = "./algorithm/memory/orderItemsState.xls";
		completeOrderItems = "";
		newOrderItems = "";
		onVehicleOrderItems = "";
		unallocatedOrderItems = "";
		routeBefore = "";
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			vehicleId2PlannedRoute.put(vehicleId, null);
		}
		for (Map.Entry<String, OrderItem> onVehicleOrderItem : id2OngoingOrderMap.entrySet()) {
			onVehicleOrderItems += onVehicleOrderItem.getKey() + " ";
		}
		onVehicleOrderItems = onVehicleOrderItems.trim();
		for (Map.Entry<String, OrderItem> unallocatedOrderItem : id2UnallocatedOrderMap.entrySet()) {
			unallocatedOrderItems += unallocatedOrderItem.getKey() + " ";
		}
		unallocatedOrderItems = unallocatedOrderItems.trim();
		File f = new File(orderItemsExelPath);
		if (!f.exists()) {
			newOrderItems = unallocatedOrderItems;
			completeOrderItems = "";
			routeBefore = "";
		} else {
			FileInputStream fis = null;
			HSSFWorkbook wb = null;
			try {
				fis = new FileInputStream(f);
				wb = new HSSFWorkbook(fis);
				HSSFSheet sheet = wb.getSheet("orde_items_state");
				List<String> list = new ArrayList<String>();
				HSSFRow lastRow = sheet.getRow(sheet.getLastRowNum());
				HSSFCell lastOnVehicleCell = lastRow.getCell(3);
				String[] lastonVehicleItems = lastOnVehicleCell.getStringCellValue().split(" ");
				String[] curOnVehicleItems = onVehicleOrderItems.split(" ");
				list = Arrays.asList(curOnVehicleItems);
				for (String lastonVehicleItem : lastonVehicleItems) {
					if (!list.contains(lastonVehicleItem)) {
						completeOrderItems += lastonVehicleItem + " ";
					}
				}
				completeOrderItems = completeOrderItems.trim();
				lastRow = sheet.getRow(sheet.getLastRowNum());
				HSSFCell lastUnallocatedItemsCell = lastRow.getCell(6);
				String[] lastUnallocatedItems = lastUnallocatedItemsCell.getStringCellValue().split(" ");
				String[] curUnallocatedItems = unallocatedOrderItems.split(" ");
				list = Arrays.asList(lastUnallocatedItems);
				for (String curUnallocatedItem : curUnallocatedItems) {
					if (!list.contains(curUnallocatedItem)) {
						newOrderItems += curUnallocatedItem + " ";
					}
				}
				newOrderItems = newOrderItems.trim();
				HSSFCell lastRouteAfterCell = lastRow.getCell(10);
				routeBefore = lastRouteAfterCell.getStringCellValue();
				if (null != fis)
					fis.close();
				String[] routeBeforeSplit = routeBefore.split("V");
				String[] completeItemsArray = completeOrderItems.split(" ");
				for (int i = 1; i < routeBeforeSplit.length; i++) {
					routeBeforeSplit[i] = routeBeforeSplit[i].trim();
					int strLen = routeBeforeSplit[i].length();
					String numStr = routeBeforeSplit[i].split(":")[0];
					String vehicleId = "V_" + numStr.substring(1, numStr.length());
					if (strLen < 15) {
						vehicleId2PlannedRoute.put(vehicleId, null);
						continue;
					}
					String routeNodesStr = routeBeforeSplit[i].split(":")[1];
					String[] routeNodes = routeNodesStr.substring(1, routeNodesStr.length() - 1).split(" ");
					List<String> nodeList = new ArrayList(Arrays.asList(routeNodes));
					String firstItemId = nodeList.get(0).split("_")[1];
					if (nodeList.size() > 0 && nodeList.get(0).substring(0, 1).equals("d")) {
						for (int k = 0; k < completeItemsArray.length; k++) {
							if (completeItemsArray[k].equals(firstItemId)) {
								nodeList.remove(0);
								break;
							}
						}
					}
					if (nodeList.size() > 0 && nodeList.get(0).substring(0, 1).equals("p")) {
						for (int k = 0; k < curOnVehicleItems.length; k++) {
							if (curOnVehicleItems[k].equals(firstItemId)) {
								nodeList.remove(0);
								break;
							}
						}
					}
					if (nodeList.size() > 0) {
						ArrayList<Node> planRoute = new ArrayList<Node>();
						for (int j = 0; j < nodeList.size(); j++) {
							List<OrderItem> deliveryItemList = new ArrayList<OrderItem>();
							List<OrderItem> pickupItemList = new ArrayList<OrderItem>();
							OrderItem deliveryOrPickupItem = null, pickupOrderItem = null;
							boolean isBothOp = false;
							String op1, orderItemId1, orderItemId2 = null;
							int op1ItemNum = 0, op2ItemNum = 0;
							op1 = nodeList.get(j).substring(0, 1);
							String[] opNumStr = nodeList.get(j).split("_");
							int len = opNumStr[0].length();
							op1ItemNum = Integer.valueOf(opNumStr[0].substring(1, len));
							String[] opCombine = nodeList.get(j).split("_");
							orderItemId1 = opCombine[1];
							if (nodeList.get(j).length() >= 30) {
								isBothOp = true;
								op2ItemNum = Integer.parseInt(nodeList.get(j).substring(16, 17));
								orderItemId2 = nodeList.get(j).substring(18, 30);
							}
							if (!isBothOp) {
								String[] indexStr = orderItemId1.split("-");
								int idEndNumber = Integer.valueOf(indexStr[1]);
								if (op1.equals("d")) {
									for (int k = 0; k < op1ItemNum; k++) {
										deliveryOrPickupItem = id2OrderItemMap.get(orderItemId1);
										deliveryItemList.add(deliveryOrPickupItem);
										orderItemId1 = orderItemId1.split("-")[0] + "-" + String.valueOf(--idEndNumber);
									}
								} else {
									for (int k = 0; k < op1ItemNum; k++) {
										deliveryOrPickupItem = id2OrderItemMap.get(orderItemId1);
										pickupItemList.add(deliveryOrPickupItem);
										orderItemId1 = orderItemId1.split("-")[0] + "-" + String.valueOf(++idEndNumber);
									}
								}
							} else {
								int id1EndNumber = Integer.valueOf(orderItemId1.substring(11, orderItemId1.length()));
								int id2EndNumber = Integer.valueOf(orderItemId2.substring(11, orderItemId2.length()));
								for (int k = 0; k < op1ItemNum; k++) {
									deliveryOrPickupItem = id2OrderItemMap.get(orderItemId1);
									deliveryItemList.add(deliveryOrPickupItem);
									orderItemId1 = orderItemId1.substring(0, 11) + String.valueOf(--id1EndNumber);
								}
								for (int k = 0; k < op2ItemNum; k++) {
									pickupOrderItem = id2OrderItemMap.get(orderItemId1);
									pickupItemList.add(pickupOrderItem);
									orderItemId1 = orderItemId1.substring(0, 11) + String.valueOf(++id2EndNumber);
								}
							}
							String factoryId;
							if (op1.equals("d")) {
								factoryId = deliveryOrPickupItem.getDeliveryFactoryId();
							} else {
								factoryId = deliveryOrPickupItem.getPickupFactoryId();
							}
							Factory factory = id2FactoryMap.get(factoryId);
							Node node = new Node(factoryId, deliveryItemList, pickupItemList, factory.getLng(),
									factory.getLat());
							planRoute.add(node);
						}
						if (nodeList.size() > 0) {
							vehicleId2PlannedRoute.put(vehicleId, planRoute);
						}

					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	/**
	 * 统计订单每个时间段的订单物料状态信息：已完成订单物料列表complete_order_items、正派送订单物料列表ongoing_order_items
	 * 未装载订单物料列表unallocated_order_items、新订单物料列表new_order_items、耗费的时间time
	 */
	private static void calculateInformationWithMergeNode(double usedTime) {
		String orderItemsExelPath = "./algorithm/memory/orderItemsState.xls";
		String completeOrderItems = "";
		String onVehicleOrderItems = "";
		String ongoingOrderItems = "";
		String unongoingOrderItems = "";
		String unallocatedOrderItems = "";
		String newOrderItems = "";
		String routeBefore = "";
		String routeAfter = "";
		for (Map.Entry<String, OrderItem> onVehicleOrderItem : id2OngoingOrderMap.entrySet()) {
			onVehicleOrderItems += onVehicleOrderItem.getKey() + " ";
		}
		onVehicleOrderItems = onVehicleOrderItems.trim();
		for (Map.Entry<String, OrderItem> unallocatedOrderItem : id2UnallocatedOrderMap.entrySet()) {
			unallocatedOrderItems += unallocatedOrderItem.getKey() + " ";
		}
		unallocatedOrderItems = unallocatedOrderItems.trim();
		File toPathDir = new File(toMemoryPath);
		if (!toPathDir.exists()) {
			toPathDir.mkdir();
		}
		preMatchingItemIds = new ArrayList<>();
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			Vehicle vehicle = id2VehicleItem.getValue();
			if (vehicle.getCarryingItems() == null && vehicle.getDes() != null) {
				List<OrderItem> pickupItemList = vehicle.getDes().getPickupItemList();
				for (OrderItem orderItem : pickupItemList) {
					preMatchingItemIds.add(orderItem.getId());
				}
			}
		}
		for (String orderItemId : preMatchingItemIds) {
			ongoingOrderItems += orderItemId + " ";
		}
		ongoingOrderItems = ongoingOrderItems.trim();
		String[] unallocatedItems = unallocatedOrderItems.split(" ");
		for (String item : unallocatedItems) {
			if (!preMatchingItemIds.contains(item)) {
				unongoingOrderItems += item + " ";
			}
		}
		File f = new File(orderItemsExelPath);
		if (!f.exists()) {
			HSSFWorkbook workbook = new HSSFWorkbook();
			HSSFSheet sheet = workbook.createSheet("orde_items_state");
			HSSFRow row = sheet.createRow(0);
			HSSFCell cell = row.createCell(0);
			cell.setCellValue("no.");
			cell = row.createCell(1);
			cell.setCellValue("deltaT");
			cell = row.createCell(2);
			cell.setCellValue("complete_order_items");
			cell = row.createCell(3);
			cell.setCellValue("onvehicle_order_items");
			cell = row.createCell(4);
			cell.setCellValue("ongoing_order_items");
			cell = row.createCell(5);
			cell.setCellValue("unongoing_order_items");
			cell = row.createCell(6);
			cell.setCellValue("unallocated_order_items");
			cell = row.createCell(7);
			cell.setCellValue("new_order_items");
			cell = row.createCell(8);
			cell.setCellValue("used_time");
			cell = row.createCell(9);
			cell.setCellValue("route_before");
			cell = row.createCell(10);
			cell.setCellValue("route_after");
			deltaT = "0000-0010";
			int vehicleNum = vehicleId2PlannedRoute.size();
			for (int i = 0; i < vehicleNum; i++) {
				String carId = "V_" + String.valueOf(i + 1);
				routeBefore += carId + ":[] ";
			}
			routeBefore = routeBefore.trim();
			routeAfter = getRouteAfterWitMergeNode();

			HSSFRow row1 = sheet.createRow(1);
			row1.createCell(0).setCellValue("0");
			row1.createCell(1).setCellValue(deltaT);
			row1.createCell(2).setCellValue(completeOrderItems);
			row1.createCell(3).setCellValue(onVehicleOrderItems);
			row1.createCell(4).setCellValue(ongoingOrderItems);
			row1.createCell(5).setCellValue(unongoingOrderItems);
			row1.createCell(6).setCellValue(unallocatedOrderItems);
			row1.createCell(7).setCellValue(unallocatedOrderItems);
			row1.createCell(8).setCellValue(usedTime);
			row1.createCell(9).setCellValue(routeBefore);
			row1.createCell(10).setCellValue(routeAfter);
			try {
				FileOutputStream fos = new FileOutputStream(orderItemsExelPath);
				workbook.write(fos);
				fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			FileInputStream fis = null;
			HSSFWorkbook wb = null;
			try {
				fis = new FileInputStream(f);
				wb = new HSSFWorkbook(fis);
				HSSFSheet sheet = wb.getSheet("orde_items_state");
				int rowLength = sheet.getLastRowNum();
				int fromT = rowLength * 10;
				int toT = rowLength * 10 + 10;
				String fromTStr = fromT < 10000 ? String.format("%04d", fromT) : String.valueOf(fromT);
				String toTStr = toT < 10000 ? String.format("%04d", toT) : String.valueOf(toT);
				deltaT = fromTStr + "-" + toTStr;
				List<String> list = new ArrayList<String>();
				HSSFRow lastRow = sheet.getRow(sheet.getLastRowNum());
				HSSFCell lastOnVehicleCell = lastRow.getCell(3);
				String[] lastonVehicleItems = lastOnVehicleCell.getStringCellValue().split(" ");
				String[] curOnVehicleItems = onVehicleOrderItems.split(" ");
				list = Arrays.asList(curOnVehicleItems);
				for (String lastonVehicleItem : lastonVehicleItems) {
					if (!list.contains(lastonVehicleItem)) {
						completeOrderItems += lastonVehicleItem + " ";
					}
				}
				completeOrderItems = completeOrderItems.trim();
				lastRow = sheet.getRow(sheet.getLastRowNum());
				HSSFCell lastUnallocatedItemsCell = lastRow.getCell(6);
				String[] lastUnallocatedItems = lastUnallocatedItemsCell.getStringCellValue().split(" ");
				String[] curUnallocatedItems = unallocatedOrderItems.split(" ");
				list = Arrays.asList(lastUnallocatedItems);
				for (String curUnallocatedItem : curUnallocatedItems) {
					if (!list.contains(curUnallocatedItem)) {
						newOrderItems += curUnallocatedItem + " ";
					}
				}
				newOrderItems = newOrderItems.trim();
				HSSFCell lastRouteAfterCell = lastRow.getCell(10);
				routeBefore = lastRouteAfterCell.getStringCellValue();
				routeAfter = getRouteAfterWitMergeNode();

				HSSFRow newRow = sheet.createRow(rowLength + 1);
				newRow.createCell(0).setCellValue(String.valueOf(rowLength));
				newRow.createCell(1).setCellValue(deltaT);
				newRow.createCell(2).setCellValue(completeOrderItems);
				newRow.createCell(3).setCellValue(onVehicleOrderItems);
				newRow.createCell(4).setCellValue(ongoingOrderItems);
				newRow.createCell(5).setCellValue(unongoingOrderItems);
				newRow.createCell(6).setCellValue(unallocatedOrderItems);
				newRow.createCell(7).setCellValue(newOrderItems);
				newRow.createCell(8).setCellValue(usedTime);
				newRow.createCell(9).setCellValue(routeBefore);
				newRow.createCell(10).setCellValue(routeAfter);

				FileOutputStream fos = new FileOutputStream(orderItemsExelPath);
				wb.write(fos);
				if (null != fis)
					fis.close();
				if (null != fos)
					fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 统计订单每个时间段的订单物料状态信息：已完成订单物料列表complete_order_items、正派送订单物料列表ongoing_order_items
	 * 未装载订单物料列表unallocated_order_items、新订单物料列表new_order_items、耗费的时间time
	 */
	private static void calculateOrderItemsInformation(double usedTime) {
		String orderItemsExelPath = "./algorithm/memory/orderItemsState.xls";
		String completeOrderItems = "";
		String onVehicleOrderItems = "";
		String ongoingOrderItems = "";
		String unongoingOrderItems = "";
		String unallocatedOrderItems = "";
		String newOrderItems = "";
		String routeBefore = "";
		String routeAfter = "";
		for (Map.Entry<String, OrderItem> onVehicleOrderItem : id2OngoingOrderMap.entrySet()) {
			onVehicleOrderItems += onVehicleOrderItem.getKey() + " ";
		}
		onVehicleOrderItems = onVehicleOrderItems.trim();
		for (Map.Entry<String, OrderItem> unallocatedOrderItem : id2UnallocatedOrderMap.entrySet()) {
			unallocatedOrderItems += unallocatedOrderItem.getKey() + " ";
		}
		unallocatedOrderItems = unallocatedOrderItems.trim();
		File toPathDir = new File(toMemoryPath);
		if (!toPathDir.exists()) {
			toPathDir.mkdir();
		}
		preMatchingItemIds = new ArrayList<>();
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			Vehicle vehicle = id2VehicleItem.getValue();
			if (vehicle.getCarryingItems() == null && vehicle.getDes() != null) {
				List<OrderItem> pickupItemList = vehicle.getDes().getPickupItemList();
				for (OrderItem orderItem : pickupItemList) {
					preMatchingItemIds.add(orderItem.getId());
				}
			}
		}
		for (String orderItemId : preMatchingItemIds) {
			ongoingOrderItems += orderItemId + " ";
		}
		ongoingOrderItems = ongoingOrderItems.trim();
		String[] unallocatedItems = unallocatedOrderItems.split(" ");
		for (String item : unallocatedItems) {
			if (!preMatchingItemIds.contains(item)) {
				unongoingOrderItems += item + " ";
			}
		}
		File f = new File(orderItemsExelPath);
		if (!f.exists()) {
			HSSFWorkbook workbook = new HSSFWorkbook();
			HSSFSheet sheet = workbook.createSheet("orde_items_state");
			HSSFRow row = sheet.createRow(0);
			HSSFCell cell = row.createCell(0);
			cell.setCellValue("no.");
			cell = row.createCell(1);
			cell.setCellValue("deltaT");
			cell = row.createCell(2);
			cell.setCellValue("complete_order_items");
			cell = row.createCell(3);
			cell.setCellValue("onvehicle_order_items");
			cell = row.createCell(4);
			cell.setCellValue("ongoing_order_items");
			cell = row.createCell(5);
			cell.setCellValue("unongoing_order_items");
			cell = row.createCell(6);
			cell.setCellValue("unallocated_order_items");
			cell = row.createCell(7);
			cell.setCellValue("new_order_items");
			cell = row.createCell(8);
			cell.setCellValue("used_time");
			cell = row.createCell(9);
			cell.setCellValue("route_before");
			cell = row.createCell(10);
			cell.setCellValue("route_after");
			deltaT = "0000-0010";
			int vehicleNum = vehicleId2PlannedRoute.size();
			for (int i = 0; i < vehicleNum; i++) {
				String carId = "V_" + String.valueOf(i + 1);
				routeBefore += carId + ":[] ";
			}
			routeBefore = routeBefore.trim();
			routeAfter = getRouteAfter();

			HSSFRow row1 = sheet.createRow(1);
			row1.createCell(0).setCellValue("0");
			row1.createCell(1).setCellValue(deltaT);
			row1.createCell(2).setCellValue(completeOrderItems);
			row1.createCell(3).setCellValue(onVehicleOrderItems);
			row1.createCell(4).setCellValue(ongoingOrderItems);
			row1.createCell(5).setCellValue(unongoingOrderItems);
			row1.createCell(6).setCellValue(unallocatedOrderItems);
			row1.createCell(7).setCellValue(unallocatedOrderItems);
			row1.createCell(8).setCellValue(usedTime);
			row1.createCell(9).setCellValue(routeBefore);
			row1.createCell(10).setCellValue(routeAfter);
			try {
				FileOutputStream fos = new FileOutputStream(orderItemsExelPath);
				workbook.write(fos);
				fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			FileInputStream fis = null;
			HSSFWorkbook wb = null;
			try {
				fis = new FileInputStream(f);
				wb = new HSSFWorkbook(fis);
				HSSFSheet sheet = wb.getSheet("orde_items_state");
				int rowLength = sheet.getLastRowNum();
				int fromT = rowLength * 10;
				int toT = rowLength * 10 + 10;
				String fromTStr = fromT < 10000 ? String.format("%04d", fromT) : String.valueOf(fromT);
				String toTStr = toT < 10000 ? String.format("%04d", toT) : String.valueOf(toT);
				deltaT = fromTStr + "-" + toTStr;
				List<String> list = new ArrayList<String>();
				HSSFRow lastRow = sheet.getRow(sheet.getLastRowNum());
				HSSFCell lastOnVehicleCell = lastRow.getCell(3);
				String[] lastonVehicleItems = lastOnVehicleCell.getStringCellValue().split(" ");
				String[] curOnVehicleItems = onVehicleOrderItems.split(" ");
				list = Arrays.asList(curOnVehicleItems);
				for (String lastonVehicleItem : lastonVehicleItems) {
					if (!list.contains(lastonVehicleItem)) {
						completeOrderItems += lastonVehicleItem + " ";
					}
				}
				completeOrderItems = completeOrderItems.trim();
				lastRow = sheet.getRow(sheet.getLastRowNum());
				HSSFCell lastUnallocatedItemsCell = lastRow.getCell(6);
				String[] lastUnallocatedItems = lastUnallocatedItemsCell.getStringCellValue().split(" ");
				String[] curUnallocatedItems = unallocatedOrderItems.split(" ");
				list = Arrays.asList(lastUnallocatedItems);
				for (String curUnallocatedItem : curUnallocatedItems) {
					if (!list.contains(curUnallocatedItem)) {
						newOrderItems += curUnallocatedItem + " ";
					}
				}
				newOrderItems = newOrderItems.trim();
				HSSFCell lastRouteAfterCell = lastRow.getCell(10);
				routeBefore = lastRouteAfterCell.getStringCellValue();
				routeAfter = getRouteAfter();

				HSSFRow newRow = sheet.createRow(rowLength + 1);
				newRow.createCell(0).setCellValue(String.valueOf(rowLength));
				newRow.createCell(1).setCellValue(deltaT);
				newRow.createCell(2).setCellValue(completeOrderItems);
				newRow.createCell(3).setCellValue(onVehicleOrderItems);
				newRow.createCell(4).setCellValue(ongoingOrderItems);
				newRow.createCell(5).setCellValue(unongoingOrderItems);
				newRow.createCell(6).setCellValue(unallocatedOrderItems);
				newRow.createCell(7).setCellValue(newOrderItems);
				newRow.createCell(8).setCellValue(usedTime);
				newRow.createCell(9).setCellValue(routeBefore);
				newRow.createCell(10).setCellValue(routeAfter);

				FileOutputStream fos = new FileOutputStream(orderItemsExelPath);
				wb.write(fos);
				if (null != fis)
					fis.close();
				if (null != fos)
					fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	/**
	 * 带有合并节点的：存储路径解每个节点的的格式为：Dn-XXXXXXXXXX-m...Dn-XXXXXXXXXX-m&Pn-XXXXXXXXXX-m...Pn-XXXXXXXXXX-m
	 * 其中n是该操作的物料个数，根据测试的instance看X的个数固定，10；m是起始物料编号-的后边的数字
	 * 
	 * @return
	 */
	private static String getRouteAfterWitMergeNode() {
		String routeStr = "";
		int vehicleNum = vehicleId2PlannedRoute.size();
		String[] vehicleRoutes = new String[vehicleNum];
		int index = 0;
		for (Entry<String, Node> id2Node : vehicleId2Destination.entrySet()) {
			if (id2Node != null && id2Node.getValue() != null) {
				Node firstNode = id2Node.getValue();
				int pickupSize = 0;
				int deliverySize = 0;
				ArrayList<OrderItem> orderItemsId;
				if (null != firstNode.getPickupItemList()) {
					pickupSize = firstNode.getPickupItemList().size();
				}
				if (null != firstNode.getDeliveryItemList()) {
					deliverySize = firstNode.getDeliveryItemList().size();
				}
				if (deliverySize > 0) {
					vehicleRoutes[index] = "[";
					orderItemsId = (ArrayList<OrderItem>) firstNode.getDeliveryItemList();
					String beforeOrderItemId = orderItemsId.get(0).getId();
					String beforeOrderId = beforeOrderItemId.split("-")[0];
					String nextOrderItemId;
					String nextOrderId;
					int itemNum = 1;
					for (int i = 1; i < deliverySize; i++) {
						nextOrderItemId = orderItemsId.get(i).getId();
						nextOrderId = nextOrderItemId.split("-")[0];
						if (beforeOrderId.equals(nextOrderId)) {
							itemNum++;
						} else {
							vehicleRoutes[index] += "d" + String.valueOf(itemNum) + "_"
									+ orderItemsId.get(i - itemNum).getId();
							itemNum = 1;
						}
						beforeOrderId = nextOrderId;
					}
					if (deliverySize > 0) {
						vehicleRoutes[index] += "d" + String.valueOf(itemNum) + "_"
								+ orderItemsId.get(deliverySize - itemNum).getId();
					}
				}
				if (pickupSize > 0) {
					if (deliverySize == 0) {
						vehicleRoutes[index] = "[";
						orderItemsId = (ArrayList<OrderItem>) firstNode.getPickupItemList();
						String beforeOrderItemId = orderItemsId.get(0).getId();
						String beforeOrderId = beforeOrderItemId.split("-")[0];
						String nextOrderItemId;
						String nextOrderId;
						int itemNum = 1;
						for (int i = 1; i < pickupSize; i++) {
							nextOrderItemId = orderItemsId.get(i).getId();
							nextOrderId = nextOrderItemId.split("-")[0];
							if (beforeOrderId.equals(nextOrderId)) {
								itemNum++;
							} else {
								vehicleRoutes[index] += "p" + String.valueOf(itemNum) + "_"
										+ orderItemsId.get(i - itemNum).getId();
								itemNum = 1;
							}
							beforeOrderId = nextOrderId;
						}
						if (pickupSize > 0) {
							vehicleRoutes[index] += "p" + String.valueOf(itemNum) + "_"
									+ orderItemsId.get(pickupSize - itemNum).getId();
						}
					} else {
						vehicleRoutes[index] = vehicleRoutes[index].trim();
						vehicleRoutes[index] += "&";
						orderItemsId = (ArrayList<OrderItem>) firstNode.getPickupItemList();
						String beforeOrderItemId = orderItemsId.get(0).getId();
						String beforeOrderId = beforeOrderItemId.split("-")[0];
						int itemNum = 1;
						for (int i = 1; i < pickupSize; i++) {
							String nextOrderItemId = orderItemsId.get(i).getId();
							String nextOrderId = nextOrderItemId.split("-")[0];
							if (beforeOrderId.equals(nextOrderId)) {
								itemNum++;
							} else {
								vehicleRoutes[index] += "p" + String.valueOf(itemNum) + "_"
										+ orderItemsId.get(i - itemNum).getId();
								itemNum = 1;
							}
							beforeOrderId = nextOrderId;
						}
						if (pickupSize > 0) {
							vehicleRoutes[index] += "p" + String.valueOf(itemNum) + "_"
									+ orderItemsId.get(pickupSize - itemNum).getId();
						}
					}
				}
				vehicleRoutes[index] += " ";
			} else {
				vehicleRoutes[index] = "[";
			}
			index++;
		}

		index = 0;
		for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
			ArrayList<Node> id2NodeList = id2NodesMap.getValue();
			if (id2NodeList != null && id2NodeList.size() > 0) {
				for (Node node : id2NodeList) {
					int pickupSize = 0;
					int deliverySize = 0;
					ArrayList<OrderItem> orderItemsId;
					if (null != node.getPickupItemList()) {
						pickupSize = node.getPickupItemList().size();
					}
					if (null != node.getDeliveryItemList()) {
						deliverySize = node.getDeliveryItemList().size();
					}
					if (deliverySize > 0) {
						orderItemsId = (ArrayList<OrderItem>) node.getDeliveryItemList();
						String beforeOrderItemId = orderItemsId.get(0).getId();
						String beforeOrderId = beforeOrderItemId.split("-")[0];
						int itemNum = 1;
						for (int i = 1; i < deliverySize; i++) {
							String nextOrderItemId = orderItemsId.get(i).getId();
							String nextOrderId = nextOrderItemId.split("-")[0];
							if (beforeOrderId.equals(nextOrderId)) {
								itemNum++;
							} else {
								vehicleRoutes[index] += "d" + String.valueOf(itemNum) + "_"
										+ orderItemsId.get(i - itemNum).getId();
								itemNum = 1;
							}
							beforeOrderId = nextOrderId;
						}
						if (deliverySize > 0) {
							vehicleRoutes[index] += "d" + String.valueOf(itemNum) + "_"
									+ orderItemsId.get(deliverySize - itemNum).getId();
						}
					}
					if (pickupSize > 0) {
						if (deliverySize > 0) {
							vehicleRoutes[index] = vehicleRoutes[index].trim();
							vehicleRoutes[index] += "&";
						}
						orderItemsId = (ArrayList<OrderItem>) node.getPickupItemList();
						String beforeOrderItemId = orderItemsId.get(0).getId();
						String beforeOrderId = beforeOrderItemId.split("-")[0];
						int itemNum = 1;
						for (int i = 1; i < pickupSize; i++) {
							String nextOrderItemId = orderItemsId.get(i).getId();
							String nextOrderId = nextOrderItemId.split("-")[0];
							if (beforeOrderId.equals(nextOrderId)) {
								itemNum++;
							} else {
								vehicleRoutes[index] += "p" + String.valueOf(itemNum) + "_"
										+ orderItemsId.get(i - itemNum).getId();
								itemNum = 1;
							}
							beforeOrderId = nextOrderId;
						}
						if (pickupSize > 0) {
							vehicleRoutes[index] += "p" + String.valueOf(itemNum) + "_"
									+ orderItemsId.get(pickupSize - itemNum).getId();
						}
					}
					vehicleRoutes[index] += " ";
				}
			}
			vehicleRoutes[index] = vehicleRoutes[index].trim();
			vehicleRoutes[index] += "]";
			index++;
		}

		for (int i = 0; i < vehicleNum; i++) {
			String carId = "V_" + String.valueOf(i + 1);
			routeStr += carId + ":" + vehicleRoutes[i] + " ";
		}
		routeStr = routeStr.trim();
		return routeStr;
	}

	/**
	 * 
	 * @return 当前时段算法找出的各车辆的路径
	 */
	private static String getRouteAfter() {
		String routeStr = "";
		int vehicleNum = vehicleId2PlannedRoute.size();
		String[] vehicleRoutes = new String[vehicleNum];
		int index = 0;
		if (vehicleId2Destination == null || vehicleId2Destination.size() == 0) {
			for (int i = 0; i < vehicleNum; i++) {
				vehicleRoutes[i] = "[";
			}
		}
		for (Entry<String, Node> id2Node : vehicleId2Destination.entrySet()) {
			if (id2Node != null && id2Node.getValue() != null) {
				Node firstNode = id2Node.getValue();
				int pickupSize = 0;
				int deliverySize = 0;
				if (null != firstNode.getPickupItemList()) {
					pickupSize = firstNode.getPickupItemList().size();
				}
				if (null != firstNode.getDeliveryItemList()) {
					deliverySize = firstNode.getDeliveryItemList().size();
				}

				if (deliverySize > 0) {
					vehicleRoutes[index] = "[d" + String.valueOf(deliverySize) + "_"
							+ firstNode.getDeliveryItemList().get(0).getId() + " ";
				}
				if (pickupSize > 0) {
					if (deliverySize == 0) {
						vehicleRoutes[index] = "[p" + String.valueOf(pickupSize) + "_"
								+ firstNode.getPickupItemList().get(0).getId() + " ";
					} else {
						vehicleRoutes[index] = vehicleRoutes[index].trim();
						vehicleRoutes[index] += "p" + String.valueOf(deliverySize) + "_"
								+ firstNode.getPickupItemList().get(0).getId() + " ";
					}
				}
			} else {
				vehicleRoutes[index] = "[";
			}
			index++;
		}

		index = 0;
		for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
			ArrayList<Node> id2NodeList = id2NodesMap.getValue();
			if (id2NodeList != null && id2NodeList.size() > 0) {
				for (Node node : id2NodeList) {
					int pickupSize = 0;
					int deliverySize = 0;
					if (null != node.getPickupItemList()) {
						pickupSize = node.getPickupItemList().size();
					}
					if (null != node.getDeliveryItemList()) {
						deliverySize = node.getDeliveryItemList().size();
					}
					if (deliverySize > 0) {
						vehicleRoutes[index] += "d" + String.valueOf(deliverySize) + "_"
								+ node.getDeliveryItemList().get(0).getId() + " ";
					}
					if (pickupSize > 0) {
						if (deliverySize > 0) {
							vehicleRoutes[index] = vehicleRoutes[index].trim();
						}
						vehicleRoutes[index] += "p" + String.valueOf(pickupSize) + "_"
								+ node.getPickupItemList().get(0).getId() + " ";
					}
				}
				vehicleRoutes[index] = vehicleRoutes[index].trim();
			}
			vehicleRoutes[index] += "]";
			index++;
		}

		for (int i = 0; i < vehicleNum; i++) {
			String carId = "V_" + String.valueOf(i + 1);
			routeStr += carId + ":" + vehicleRoutes[i] + " ";
		}
		routeStr = routeStr.trim();
		return routeStr;
	}

	/**
	 * 
	 * @return 当前时段算法找出的各车辆的路径
	 */
	private static String getRouteAfterBackup() {
		String routeStr = "";
		int vehicleNum = vehicleId2PlannedRoute.size();
		String[] vehicleRoutes = new String[vehicleNum];
		int index = 0;
		for (Entry<String, Node> id2Node : vehicleId2Destination.entrySet()) {
			if (id2Node != null && id2Node.getValue() != null) {
				Node firstNode = id2Node.getValue();
				int pickupSize = 0;
				int deliverySize = 0;
				if (null != firstNode.getPickupItemList()) {
					pickupSize = firstNode.getPickupItemList().size();
				}
				if (null != firstNode.getDeliveryItemList()) {
					deliverySize = firstNode.getDeliveryItemList().size();
				}
				if (0 < pickupSize) {
					vehicleRoutes[index] = "[p" + String.valueOf(pickupSize) + "_"
							+ firstNode.getPickupItemList().get(0).getId() + " ";
				}
				if (deliverySize > 0) {
					if (pickupSize == 0) {
						vehicleRoutes[index] = "[d" + String.valueOf(deliverySize) + "_"
								+ firstNode.getDeliveryItemList().get(0).getId() + " ";
					} else {
						vehicleRoutes[index] = vehicleRoutes[index].trim();
						vehicleRoutes[index] += "d" + String.valueOf(deliverySize) + "_"
								+ firstNode.getDeliveryItemList().get(0).getId() + " ";
					}
				}
			} else {
				vehicleRoutes[index] = "[";
			}
			index++;
		}

		index = 0;
		for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
			ArrayList<Node> id2NodeList = id2NodesMap.getValue();
			if (id2NodeList != null && id2NodeList.size() > 0) {
				for (Node node : id2NodeList) {
					int pickupSize = 0;
					int deliverySize = 0;
					if (null != node.getPickupItemList()) {
						pickupSize = node.getPickupItemList().size();
					}
					if (null != node.getDeliveryItemList()) {
						deliverySize = node.getDeliveryItemList().size();
					}
					if (0 < pickupSize) {
						vehicleRoutes[index] += "p" + String.valueOf(pickupSize) + "_"
								+ node.getPickupItemList().get(0).getId() + " ";
					}
					if (deliverySize > 0) {
						if (pickupSize > 0) {
							vehicleRoutes[index] = vehicleRoutes[index].trim();
						}
						vehicleRoutes[index] += "d" + String.valueOf(deliverySize) + "_"
								+ node.getDeliveryItemList().get(0).getId() + " ";
					}
				}
			}
			vehicleRoutes[index] = vehicleRoutes[index].trim();
			vehicleRoutes[index] += "]";
			index++;
		}

		for (int i = 0; i < vehicleNum; i++) {
			String carId = "V_" + String.valueOf(i + 1);
			routeStr += carId + ":" + vehicleRoutes[i] + " ";
		}
		routeStr = routeStr.trim();
		return routeStr;

	}

	/**
	 * 复制三个模拟器输入文件和上个时段解文件至./algorithm/data_interaction中
	 * 
	 * @throws IOException
	 */
	private static void copyInputFile() throws IOException {
		File toPathDir = new File(toMemoryPath);
		if (!toPathDir.exists()) {
			toPathDir.mkdir();
		}
		File fromDir = new File(input_directory);
		if (fromDir.isDirectory()) {
			File[] listFiles = fromDir.listFiles();
			for (File file : listFiles) {
				String inputFileName = file.getName();
				File fromFile = new File(input_directory + "/" + inputFileName);
				if (!"output".equals(inputFileName.substring(0, 6))) {
					FileInputStream is = new FileInputStream(fromFile);
					FileOutputStream os = new FileOutputStream(new File(toMemoryPath, deltaT + "_" + inputFileName));
					byte[] b = new byte[1024];
					int temp = 0;
					while ((temp = is.read(b)) != -1) {
						os.write(b, 0, temp);
					}
					is.close();
					os.close();
				}
			}

		}
	}

	/**
	 * 复制三个模拟器输入文件至./algorithm/data_interaction中
	 * 
	 * @throws IOException
	 */
	private static void copyInputFile2XLS() throws IOException {
		File fromDir = new File(input_directory);
		if (fromDir.isDirectory()) {
			File[] listFiles = fromDir.listFiles();
			for (File file : listFiles) {
				String inputFileName = file.getName();
				if (!"output".equals(inputFileName.substring(0, 6))
						&& !"solution".equals(inputFileName.substring(0, 8))) {
					String outputFileName = inputFileName.substring(0, inputFileName.length() - 5);
					JSONParser jsonParser = new JSONParser();
					try {
						Object obj = jsonParser.parse(new FileReader(input_directory + "/" + inputFileName));
						JSONArray inputFileJA = (JSONArray) obj;
						String inputJsonTmp = inputFileJA.toJSONString();
						String inputJsonStr = "{inputFile:" + inputJsonTmp + "}";
						org.json.JSONObject output = new org.json.JSONObject(inputJsonStr);
						org.json.JSONArray docs = output.getJSONArray("inputFile");
						File cpFile = new File(toMemoryPath + "/" + deltaT + "_" + outputFileName + ".csv");
						String csv = CDL.toString(docs);
						FileUtils.writeStringToFile(cpFile, csv);

					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}

		}
	}

	private static void readInputFile() {
		/**
		 * 开始读取输入文件的
		 */
		readCsvFile();
		readJsonInfo();
	}

	private static void readJsonInfo() {
		readVehicleInfo();
		readUnallocatedOrderItems();
		readOngoingOrderItems();
		id2OrderItemMap = new LinkedHashMap<>();
		id2OrderItemMap.putAll(id2UnallocatedOrderMap);
		id2OrderItemMap.putAll(id2OngoingOrderMap);
		getVehicleDict();
	}

	/**
	 * 对所读取的订单、车辆和工厂信息进行关联整合到每辆车而得到带有订单和路径的车辆信息
	 * 
	 * @param vehicleList 车辆基本信息列表vehicleInfoList,id2OrderItemMap,id2FactoryMap
	 * @param id2Order    未装载和已经装载的订单集
	 * @param id2Factory  工厂信息集
	 * @return
	 */
	private static void getVehicleDict() {
		Iterator<VehicleInfo> it = vehicleInfoList.iterator();
		while (it.hasNext()) {
			VehicleInfo vehicleInfo = it.next();
			String vehicleId = vehicleInfo.getId();
			int operationTime = vehicleInfo.getOperationTime();
			int capacity = vehicleInfo.getCapacity();
			String gpsId = vehicleInfo.getGpsId();
			List<String> carryingItemIdList = vehicleInfo.getCarryingItems();
			List<OrderItem> carryingItemList = new ArrayList<>();
			for (int i = 0; i < carryingItemIdList.size(); i++) {
				OrderItem orderItem = id2OrderItemMap.get(carryingItemIdList.get(i));
				carryingItemList.add(orderItem); 
			}
			if (0 == carryingItemList.size()) {
				carryingItemList = null;
			}
			Node des = null;
			Destination orignDes = vehicleInfo.getDestination();
			if (null != orignDes) {
				int arrTime = orignDes.getArriveTime();
				int leaveTime = orignDes.getLeaveTime();
				String factoryId = orignDes.getFactoryId();
				double lng = (id2FactoryMap.get(factoryId)).getLng();
				double lat = (id2FactoryMap.get(factoryId)).getLat();
				List<String> deliveryIdList = orignDes.getDeliveryItemList();
				List<OrderItem> deliveryItemsList = new ArrayList<OrderItem>();
				for (int i = 0; i < deliveryIdList.size(); i++) {
					OrderItem orderItem = id2OrderItemMap.get(deliveryIdList.get(i));
					deliveryItemsList.add(orderItem);
				}
				List<String> pickupIdList = orignDes.getPickupItemList();
				List<OrderItem> pickupItemsList = new ArrayList<OrderItem>();
				for (int i = 0; i < pickupIdList.size(); i++) {
					OrderItem orderItem = id2OrderItemMap.get(pickupIdList.get(i));
					pickupItemsList.add(orderItem);
				}
				des = new Node(factoryId, deliveryItemsList, pickupItemsList, arrTime, leaveTime, lng, lat);
			}
			String curFactoryId = vehicleInfo.getCurFactoryId();
			int arriveTimeAtCurrentFactory = (int) vehicleInfo.getArriveTimeAtCurrentFactory();
			int leaveTimeAtCurrentFactory = (int) vehicleInfo.getLeaveTimeAtCurrentFactory();
			int updateTime = (int) vehicleInfo.getUpdateTime();
			if (!id2VehicleMap.containsKey(vehicleId)) {
				Vehicle vehicle = new Vehicle(vehicleId, gpsId, operationTime, capacity, carryingItemList);
				vehicle.setDes(des);
				vehicle.setCurPositionInfo(curFactoryId, updateTime, arriveTimeAtCurrentFactory,
						leaveTimeAtCurrentFactory);
				id2VehicleMap.put(vehicleId, vehicle);
			}
		}
	}

	/**
	 * 读vehicle_info.json文件
	 */
	private static void readVehicleInfo() {
		String filename;
		JSONParser jsonParser = new JSONParser();
		if (isTest) {
			filename = debugPeriod + "_vehicle_info.json";
		} else {
			filename = "vehicle_info.json";
		}
		try (FileReader reader = new FileReader(input_directory + '/' + filename)) {
			JSONArray vehicleInfoItr = (JSONArray) jsonParser.parse(reader);
			Iterator<JSONObject> iterator;
			Iterator<String> itemsIterator;
			iterator = vehicleInfoItr.iterator();
			while (iterator.hasNext()) {
				JSONObject vehicleJson = iterator.next();
				String id = (String) vehicleJson.get("id");
				String gpsId = (String) vehicleJson.get("gps_id");
				String curFactoryId = (String) vehicleJson.get("cur_factory_id");
				int operationTime = ((Number) vehicleJson.get("operation_time")).intValue();
				int capacity = ((Number) vehicleJson.get("capacity")).intValue();
				int updateTime = ((Number) vehicleJson.get("update_time")).intValue();
				int arriveTimeAtCurrentFactory = ((Number) vehicleJson.get("arrive_time_at_current_factory"))
						.intValue();
				int leaveTimeAtCurrentFactory = ((Number) vehicleJson.get("leave_time_at_current_factory")).intValue();
				JSONArray carryingItemsJson = (JSONArray) vehicleJson.get("carrying_items");
				itemsIterator = carryingItemsJson.iterator();
				List<String> carryingItemsList = new ArrayList<>();
				while (itemsIterator.hasNext()) {
					String idStr = (String) itemsIterator.next();
					carryingItemsList.add(idStr);
				}
				Destination des = null;
				JSONObject destinationJson = (JSONObject) vehicleJson.get("destination");
				if (null != destinationJson) {
					String factoryId = (String) destinationJson.get("factory_id");
					int arriveTime = ((Number) destinationJson.get("arrive_time")).intValue();
					int leaveTime = ((Number) destinationJson.get("leave_time")).intValue();
					JSONArray pickupItemsJson = (JSONArray) destinationJson.get("pickup_item_list");
					itemsIterator = pickupItemsJson.iterator();
					List<String> pickupItemsList = new ArrayList<>();
					while (itemsIterator.hasNext()) {
						String idStr = (String) itemsIterator.next();
						pickupItemsList.add(idStr);
					}
					JSONArray deliveryItemsJson = (JSONArray) destinationJson.get("delivery_item_list");
					itemsIterator = deliveryItemsJson.iterator();
					List<String> deliveryItemsList = new ArrayList<>();
					while (itemsIterator.hasNext()) {
						String idStr = (String) itemsIterator.next();
						deliveryItemsList.add(idStr);
					}
					des = new Destination(factoryId, deliveryItemsList, pickupItemsList, arriveTime, leaveTime);
				}
				VehicleInfo vehicle = new VehicleInfo(id, operationTime, capacity, gpsId, updateTime, curFactoryId,
						arriveTimeAtCurrentFactory, leaveTimeAtCurrentFactory, carryingItemsList, des);
				vehicleInfoList.add(vehicle);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (org.json.simple.parser.ParseException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 读取unallocated_order_items.json文件获取id-unallocatedOrder键值对集
	 */
	private static void readUnallocatedOrderItems() {
		String filename;
		JSONParser jsonParser = new JSONParser();
		if (isTest) {
			filename = debugPeriod + "_unallocated_order_items.json";
		} else {
			filename = "unallocated_order_items.json";
		}
		try (FileReader reader = new FileReader(input_directory + '/' + filename)) {
			JSONArray unallocatedOrderList = (JSONArray) jsonParser.parse(reader);
			Iterator<JSONObject> iterator;
			iterator = unallocatedOrderList.iterator();
			while (iterator.hasNext()) {
				JSONObject unallocatedOrderJson = iterator.next();
				String id = (String) unallocatedOrderJson.get("id");
				String type = (String) unallocatedOrderJson.get("type");
				String orderId = (String) unallocatedOrderJson.get("order_id");
				String pickupFactoryId = (String) unallocatedOrderJson.get("pickup_factory_id");
				String deliveryFactoryId = (String) unallocatedOrderJson.get("delivery_factory_id");
				int creationTime = ((Long) unallocatedOrderJson.get("creation_time")).intValue();
				int committedCompletionTime = ((Long) unallocatedOrderJson.get("committed_completion_time")).intValue();
				int loadTime = ((Long) unallocatedOrderJson.get("load_time")).intValue();
				int unloadTime = ((Long) unallocatedOrderJson.get("unload_time")).intValue();
				int deliverState = ((Long) unallocatedOrderJson.get("delivery_state")).intValue();
				double demand = Double.parseDouble(String.valueOf(unallocatedOrderJson.get("demand")));
				OrderItem unallocatedOrder = new OrderItem(id, type, orderId, demand, pickupFactoryId,
						deliveryFactoryId, creationTime, committedCompletionTime, loadTime, unloadTime, deliverState);
				if (!id2UnallocatedOrderMap.containsKey(id)) {
					id2UnallocatedOrderMap.put(id, unallocatedOrder);
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (org.json.simple.parser.ParseException e) {
			e.printStackTrace();
		}
	}

	private static void readOngoingOrderItems() {
		String filename;
		JSONParser jsonParser = new JSONParser();
		if (isTest) {
			filename = debugPeriod + "_ongoing_order_items.json";
		} else {
			filename = "ongoing_order_items.json";
		}
		try (FileReader reader = new FileReader(input_directory + '/' + filename)) {
			JSONArray ongoingOrderList = (JSONArray) jsonParser.parse(reader);
			Iterator<JSONObject> iterator;
			iterator = ongoingOrderList.iterator();
			while (iterator.hasNext()) {
				JSONObject ongoningOrderJson = iterator.next();
				String id = (String) ongoningOrderJson.get("id");
				String type = (String) ongoningOrderJson.get("type");
				String orderId = (String) ongoningOrderJson.get("order_id");
				String pickupFactoryId = (String) ongoningOrderJson.get("pickup_factory_id");
				String deliveryFactoryId = (String) ongoningOrderJson.get("delivery_factory_id");
				int creationTime = ((Long) ongoningOrderJson.get("creation_time")).intValue();
				int committedCompletionTime = ((Long) ongoningOrderJson.get("committed_completion_time")).intValue();
				int loadTime = ((Long) ongoningOrderJson.get("load_time")).intValue();
				int unloadTime = ((Long) ongoningOrderJson.get("unload_time")).intValue();
				int deliveryState = ((Long) ongoningOrderJson.get("delivery_state")).intValue();
				double demand = Double.parseDouble(String.valueOf(ongoningOrderJson.get("demand")));
				OrderItem ongoingOrder = new OrderItem(id, type, orderId, demand, pickupFactoryId, deliveryFactoryId,
						creationTime, committedCompletionTime, loadTime, unloadTime, deliveryState);
				if (!id2OngoingOrderMap.containsKey(id)) {
					id2OngoingOrderMap.put(id, ongoingOrder);
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (org.json.simple.parser.ParseException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 获取到的是id-factory的Map集合
	 */
	private static void readCsvFile() {
		String factoryInfoFilePath = "./benchmark/factory_info.csv";
		String routeInfoFilePath = "./benchmark/route_info.csv";
		ReadFactoryCsvUtil factoryCSV = new ReadFactoryCsvUtil(factoryInfoFilePath);
		id2FactoryMap = (HashMap<String, Factory>) factoryCSV.getFactoryInfo();
		ReadRouteInfoCsvUtil routeInfoCSV = new ReadRouteInfoCsvUtil(routeInfoFilePath);
		id2RouteMap = (HashMap<String, String>) routeInfoCSV.getRouteInfo();
	}

	private static void dispatchOrders2Vehicles() {
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			Vehicle vehicle = id2VehicleItem.getValue();
			List<OrderItem> carrayingItemList = vehicle.getCarryingItems();
			vehicleId2PlannedRoute.put(vehicleId, null);
			if (null != carrayingItemList && carrayingItemList.size() > 0) {

				List<OrderItem> reverseCarrayingOrderList = new ArrayList<>();
				List<OrderItem> deliveryItemList = new ArrayList<OrderItem>();
				for (int i = carrayingItemList.size() - 1; i >= 0; i--) {
					reverseCarrayingOrderList.add(carrayingItemList.get(i));
				}
				String factoryId = reverseCarrayingOrderList.get(0).getDeliveryFactoryId();
				for (OrderItem orderItem : reverseCarrayingOrderList) {
					if (orderItem.getDeliveryFactoryId().equals(factoryId)) {
						deliveryItemList.add(orderItem);
					} else {
						Factory factory = id2FactoryMap.get(factoryId);
						Node node = new Node(factoryId, deliveryItemList, new ArrayList<>(), factory.getLng(),
								factory.getLat());
						vehicleId2PlannedRoute.put(vehicleId, new ArrayList<Node>() {
							{
								add(node);
							}
						});
						deliveryItemList.clear();
						deliveryItemList.add(orderItem);
						factoryId = orderItem.getDeliveryFactoryId();
					}
				}
				if (0 < deliveryItemList.size()) {
					Factory factory = id2FactoryMap.get(factoryId);
					Node node = new Node(factoryId, deliveryItemList, new ArrayList<>(), factory.getLng(),
							factory.getLat());
					vehicleId2PlannedRoute.put(vehicleId, new ArrayList<Node>() {
						{
							add(node);
						}
					});
				}
			}
		}
		preMatchingItemIds = new ArrayList<>();
		for (

		Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			Vehicle vehicle = id2VehicleItem.getValue();
			if (vehicle.getCarryingItems() == null && vehicle.getDes() != null) {
				List<OrderItem> pickupItemList = vehicle.getDes().getPickupItemList();
				ArrayList<Node> nodeList = (ArrayList<Node>) createPickupAndDeliveryNodes(pickupItemList);
				vehicleId2PlannedRoute.put(vehicleId, nodeList);
				for (OrderItem orderItem : pickupItemList) {
					preMatchingItemIds.add(orderItem.getId());
				}
			}
		}
		int capacity = 0;
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			Vehicle vehicle = id2VehicleItem.getValue();
			capacity = vehicle.getBoardCapacity();
			break;
		}
		LinkedHashMap<String, ArrayList<OrderItem>> orderId2Items = new LinkedHashMap<>(); 
		for (Map.Entry<String, OrderItem> id2UnallocatedOrderItem : id2UnallocatedOrderMap.entrySet()) {
			String itemId = id2UnallocatedOrderItem.getKey();
			OrderItem orderItem = id2UnallocatedOrderItem.getValue();
			if (preMatchingItemIds.contains(itemId)) {
				continue;
			}
			String orderId = orderItem.getOrderId();
			if (!orderId2Items.containsKey(orderId)) {
				orderId2Items.put(orderId, new ArrayList<OrderItem>() {
					{
						add(orderItem);
					}
				});
			} else {
				ArrayList<OrderItem> orderId2ItemList = orderId2Items.get(orderId);
				orderId2ItemList.add(orderItem);
				orderId2Items.put(orderId, orderId2ItemList);
			}

		}
		int vehicleIndex = 0;
		ArrayList<Vehicle> vehicleList = new ArrayList<Vehicle>();
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			vehicleList.add(id2VehicleItem.getValue());
		}
		for (Map.Entry<String, ArrayList<OrderItem>> orderId2Item : orderId2Items.entrySet()) {
			String orderId = orderId2Item.getKey();
			ArrayList<OrderItem> items = orderId2Item.getValue();
			double demand = 0;
			for (OrderItem orderItem : items) {
				demand += orderItem.getDemand();
			}
			if (demand > capacity) {
				double curDemand = 0;
				ArrayList<OrderItem> tmpItems = new ArrayList<OrderItem>();
				for (OrderItem item : items) {
					if (curDemand + item.getDemand() > capacity) {
						ArrayList<Node> nodeList = (ArrayList<Node>) createPickupAndDeliveryNodes(tmpItems);
						if (nodeList == null || nodeList.get(0) == null || nodeList.get(0) == null) {
							continue;
						}
						Vehicle vehicle = vehicleList.get(vehicleIndex);
						if (null == vehicleId2PlannedRoute.get(vehicle.getId())
								|| 0 == vehicleId2PlannedRoute.get(vehicle.getId()).size()) {
							vehicleId2PlannedRoute.put(vehicle.getId(), nodeList);
						} else {
							ArrayList<Node> tmpNodeList = vehicleId2PlannedRoute.get(vehicle.getId());
							for (Node node : nodeList) {
								tmpNodeList.add(node);
							}
							vehicleId2PlannedRoute.put(vehicle.getId(), tmpNodeList);
						}

						vehicleIndex = (vehicleIndex + 1) % vehicleList.size();
						tmpItems.clear();
						curDemand = 0;
					}
					tmpItems.add(item);
					curDemand += item.getDemand();
				}
				if (tmpItems.size() > 0) {
					ArrayList<Node> nodeList = (ArrayList<Node>) createPickupAndDeliveryNodes(tmpItems);
					if (nodeList == null || nodeList.get(0) == null) {
						continue;
					}
					Vehicle vehicle = vehicleList.get(vehicleIndex);
					if (null == vehicleId2PlannedRoute.get(vehicle.getId())
							|| 0 == vehicleId2PlannedRoute.get(vehicle.getId()).size()) {
						vehicleId2PlannedRoute.put(vehicle.getId(), nodeList);
					} else {
						ArrayList<Node> tmpNodeList = vehicleId2PlannedRoute.get(vehicle.getId());
						for (Node node : nodeList) {
							tmpNodeList.add(node);
						}
						vehicleId2PlannedRoute.put(vehicle.getId(), tmpNodeList);
					}
				}
			} else {
				ArrayList<Node> nodeList = (ArrayList<Node>) createPickupAndDeliveryNodes(items);
				if (nodeList == null || nodeList.get(0) == null || nodeList.get(0) == null) {
					continue;
				}
				Vehicle vehicle = vehicleList.get(vehicleIndex);
				if (null == vehicleId2PlannedRoute.get(vehicle.getId())
						|| 0 == vehicleId2PlannedRoute.get(vehicle.getId()).size()) {
					vehicleId2PlannedRoute.put(vehicle.getId(), nodeList);
				} else {
					ArrayList<Node> tmpNodeList = vehicleId2PlannedRoute.get(vehicle.getId());
					for (Node node : nodeList) {
						tmpNodeList.add(node);
					}
					vehicleId2PlannedRoute.put(vehicle.getId(), tmpNodeList);
				}

			}
			vehicleIndex = (vehicleIndex + 1) % vehicleList.size();
		}

		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			Vehicle vehicle = id2VehicleItem.getValue();
			ArrayList<Node> orignPlannedRoute = vehicleId2PlannedRoute.get(vehicleId);
			ArrayList<Node> plannedRoute = new ArrayList<Node>();
			destination = null;
			if (vehicle.getDes() != null) {
				if (null == orignPlannedRoute || 0 == orignPlannedRoute.size()) {
					System.err.println("Planned route of vehicle " + vehicleId + " is wrong");
				} else {
					destination = orignPlannedRoute.get(0);
					destination.setArriveTime(vehicle.getDes().getArriveTime());
					orignPlannedRoute.remove(0);
				}
			} else if (orignPlannedRoute != null && orignPlannedRoute.size() > 0) {
				destination = orignPlannedRoute.get(0);
				orignPlannedRoute.remove(0);
			}
			if (null != orignPlannedRoute && 0 == orignPlannedRoute.size()) {
				orignPlannedRoute = null;
			}
			vehicleId2Destination.put(vehicleId, destination);
			vehicleId2PlannedRoute.put(vehicleId, orignPlannedRoute);
		}
	}

	/**
	 * 为每个物料创建取货和派送节点
	 * 
	 * @return List<Node> 即两个节点，第一个是取货节点，第二个送货节点
	 */
	private static List<Node> createPickupAndDeliveryNodes(List<OrderItem> itemList) {
		List<Node> pickupAndDeliveryNode = new ArrayList<>();
		String pickupFactoryId = "";
		String deliveryFactoryId = "";
		if (itemList.size() > 0) {
			pickupFactoryId = itemList.get(0).getPickupFactoryId();
			deliveryFactoryId = itemList.get(0).getDeliveryFactoryId();
			for (OrderItem orderItem : itemList) {
				if (!orderItem.getPickupFactoryId().equals(pickupFactoryId)) {
					System.out.println("The pickup factory of these items is not the same");
					pickupFactoryId = "";
					break;
				}
			}
			for (OrderItem orderItem : itemList) {
				if (!orderItem.getDeliveryFactoryId().equals(deliveryFactoryId)) {
					System.out.println("The delivery factory of these items is not the same");
					deliveryFactoryId = "";
					break;
				}
			}
		}
		if (0 == pickupFactoryId.length() || 0 == deliveryFactoryId.length()) {
			return null;
		}
		Factory pickupFactory = id2FactoryMap.get(pickupFactoryId);
		Factory deliveryFactory = id2FactoryMap.get(deliveryFactoryId);
		List<OrderItem> pickupItemList = new ArrayList<>();
		for (int i = 0; i < itemList.size(); i++) {
			pickupItemList.add(itemList.get(i));
		}
		Node pickupNode = new Node(pickupFactory.getFactoryId(), new ArrayList<>(), pickupItemList,
				pickupFactory.getLng(), pickupFactory.getLat());
		List<OrderItem> deliveryItemList = new ArrayList<>();
		for (int i = itemList.size() - 1; i >= 0; i--) {
			deliveryItemList.add(itemList.get(i));
		}
		Node deliveryNode = new Node(deliveryFactory.getFactoryId(), deliveryItemList, new ArrayList<>(),
				deliveryFactory.getLng(), deliveryFactory.getLat());
		pickupAndDeliveryNode.add(pickupNode);
		pickupAndDeliveryNode.add(deliveryNode);
		return pickupAndDeliveryNode;
	}

	/**
	 * 带有延迟派送的输出方式
	 */
	private static void outputJsonWithDelayDispatchTime() {
		isDelayDispatch();
		getOutputSolution();
		writeDestinationJson2FileWithDelayTime(vehicleId2Destination);
		writeRouteJson2FileWithDelayTime(vehicleId2PlannedRoute);
	}


	/**
	 * 检查规划的路径是否允许延迟订单执行 210702
	 * 
	 * @return
	 */
	private static void isDelayDispatch() {
		int vehicleNum = id2VehicleMap.size();
		int minSlackTime;
		int slackTime = 0;
		emergencyIndex = new int[vehicleNum];
		Arrays.fill(emergencyIndex, -1);
		double drivingDistance = 0;
		double overTimeSum = 0;
		HashMap dockTable = new HashMap<String, ArrayList<Integer[]>>();
		int n = 0;
		int[] currNode = new int[vehicleNum];
		int[] currTime = new int[vehicleNum];
		int[] nNode = new int[vehicleNum];
		int index = 0;
		for (Map.Entry<String, Vehicle> id2Vehicle : id2VehicleMap.entrySet()) {
			String vehicleId = id2Vehicle.getKey();
			Vehicle vehicle = id2Vehicle.getValue();
			double distance = 0;
			int time = 0;
			if (vehicleId2PlannedRoute.get(vehicleId) != null && vehicleId2PlannedRoute.get(vehicleId).size() > 0) {
				currNode[index] = 0;
				nNode[index] = vehicleId2PlannedRoute.get(vehicleId).size();
				if (vehicle.getDes() == null) {
					if (vehicle.getCurFactoryId().equals(vehicleId2PlannedRoute.get(vehicleId).get(0).getId())) {
						currTime[index] = vehicle.getGpsUpdateTime();
					} else {
						String disAndTimeStr = id2RouteMap.get(
								vehicle.getCurFactoryId() + "+" + vehicleId2PlannedRoute.get(vehicleId).get(0).getId());
						if (disAndTimeStr == null)
							System.err.println("no distance");
						String[] disAndTime = disAndTimeStr.split("\\+");
						time = Integer.parseInt(disAndTime[1]);
						currTime[index] = vehicle.getGpsUpdateTime() + time;
					}
				} else {
					if (vehicle.getCurFactoryId() != null && vehicle.getCurFactoryId().length() > 0) {
						if (!vehicle.getCurFactoryId().equals(vehicleId2PlannedRoute.get(vehicleId).get(0).getId())) {
							currTime[index] = vehicle.getLeaveTimeAtCurrentFactory();
							String disAndTimeStr = id2RouteMap.get(vehicle.getCurFactoryId() + "+"
									+ vehicleId2PlannedRoute.get(vehicleId).get(0).getId());
							String[] disAndTime = disAndTimeStr.split("\\+");
							time = Integer.parseInt(disAndTime[1]);
							currTime[index] += time;
						} else {
							currTime[index] = vehicle.getLeaveTimeAtCurrentFactory();

						}
					} else {
						currTime[index] = vehicle.getDes().getArriveTime();
					}
				}
				n = n + 1;
			} else {
				currNode[index] = Integer.MAX_VALUE;
				currTime[index] = Integer.MAX_VALUE;
				nNode[index] = 0;
			}
			index++;
		}

		boolean flag = false;
		while (n > 0) {
			int minT = Integer.MAX_VALUE, minT2VehicleIndex = 0;
			int tTrue = minT, idx = 0;
			for (int i = 0; i < vehicleNum; i++) {
				if (currTime[i] < minT) {
					minT = currTime[i];
					minT2VehicleIndex = i;
				}
			}
			String minT2VehicleId = "V_" + String.valueOf(minT2VehicleIndex + 1);

			ArrayList<Node> minTNodeList;
			minTNodeList = vehicleId2PlannedRoute.get(minT2VehicleId);
			Node minTNode = minTNodeList.get(currNode[minT2VehicleIndex]);
			if (minTNode.getDeliveryItemList() != null && minTNode.getDeliveryItemList().size() > 0) {
				for (OrderItem orderItem : minTNode.getDeliveryItemList()) {
					int commitCompleteTime = orderItem.getCommittedCompletionTime();
					slackTime = commitCompleteTime - minT;
					if (slackTime < SLACK_TIME_THRESHOLD) {
						emergencyIndex[minT2VehicleIndex] = currNode[minT2VehicleIndex];
						break;
					}
				}

			}
			/* 遍历 dockTable */
			ArrayList<Integer> usedEndTime = new ArrayList<>();
			ArrayList<Integer[]> timeSlots = (ArrayList<Integer[]>) dockTable.get(minTNode.getId());
			if (timeSlots != null) {
				for (int i = 0; i < timeSlots.size(); i++) {
					Integer[] timeSlot = timeSlots.get(i);
					if (timeSlot[1] <= minT) {
						timeSlots.remove(i);/* 这个 timeslot 在 minT 时刻没有占用 dock，并且在后面的时刻也不会占用 dock */
						i--;
					} else if (timeSlot[0] <= minT && minT < timeSlot[1]) {
						usedEndTime.add(timeSlot[1]);/* 记录这个 timeslot 占用 dock 时段的最后时间点 */
					} else {
						System.err.println("------------ timeslot.start>minT--------------");
					}
				}
			}
			if (usedEndTime.size() < 6) {
				tTrue = minT;
			} else {
				flag = true;
				idx = usedEndTime.size() - 6;
				usedEndTime.sort(new Comparator<Integer>() {
					public int compare(Integer a, Integer b) {
						if (a < b)
							return -1;
						else
							return 1;
					}
				});
				tTrue = usedEndTime.get(idx);
			}

			int serviceTime = minTNodeList.get(currNode[minT2VehicleIndex]).getServiceTime();
			String curFatoryId = minTNodeList.get(currNode[minT2VehicleIndex]).getId();
			currNode[minT2VehicleIndex]++;
			while (currNode[minT2VehicleIndex] < nNode[minT2VehicleIndex]
					&& curFatoryId.equals(minTNodeList.get(currNode[minT2VehicleIndex]).getId())) {
				if (minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList() != null
						&& minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList().size() > 0) {
					for (OrderItem orderItem : minTNodeList.get(currNode[minT2VehicleIndex]).getDeliveryItemList()) {
						int commitCompleteTime = orderItem.getCommittedCompletionTime();
						slackTime = commitCompleteTime - minT;
						if (slackTime < SLACK_TIME_THRESHOLD) {
							emergencyIndex[minT2VehicleIndex] = currNode[minT2VehicleIndex];
							break;
						}
					}
				}
				serviceTime += minTNodeList.get(currNode[minT2VehicleIndex]).getServiceTime();
				currNode[minT2VehicleIndex]++;
			}

			if (currNode[minT2VehicleIndex] >= nNode[minT2VehicleIndex]) {
				n = n - 1;
				currNode[minT2VehicleIndex] = Integer.MAX_VALUE;
				currTime[minT2VehicleIndex] = Integer.MAX_VALUE;
				nNode[minT2VehicleIndex] = 0;
			} else {
				String disAndTimeStr = id2RouteMap
						.get(curFatoryId + "+" + minTNodeList.get(currNode[minT2VehicleIndex]).getId());
				String[] disAndTime = disAndTimeStr.split("\\+");
				int time = Integer.parseInt(disAndTime[1]);
				currTime[minT2VehicleIndex] = tTrue + APPROACHING_DOCK_TIME + serviceTime + time;
			}

			Integer[] tw = new Integer[] { minT, tTrue + APPROACHING_DOCK_TIME + serviceTime };
			ArrayList<Integer[]> twList = (ArrayList<Integer[]>) dockTable.get(minTNode.getId());
			if (null == twList) {
				twList = new ArrayList<>();
			}
			twList.add(tw);
			dockTable.put(minTNode.getId(), twList);
		}
		/*------------检查路径的完整性----------------*/
		int idx = 0;
		for (Entry<String, ArrayList<Node>> id2NodesMap : vehicleId2PlannedRoute.entrySet()) {
			String vehicleId = id2NodesMap.getKey();
			Vehicle vehicle = id2VehicleMap.get(vehicleId);
			ArrayList<Node> id2NodeList = id2NodesMap.getValue();
			if (id2NodeList != null && id2NodeList.size() > 0
					&& (emergencyIndex[idx] > -1 || vehicle.getCarryingItems() != null || vehicle.getDes() != null)) {
				ArrayList<OrderItem> dItemList = new ArrayList<OrderItem>();
				ArrayList<OrderItem> carryingItems = (ArrayList<OrderItem>) vehicle.getCarryingItems();
				if (carryingItems != null && carryingItems.size() > 0) {
					for (int i = carryingItems.size() - 1; i >= 0; i--) {
						dItemList.add(carryingItems.get(i));
					}
				}
				for (int k = 0; k < id2NodeList.size() && emergencyIndex[idx] > -1; k++) {
					Node node = id2NodeList.get(k);
					ArrayList<OrderItem> deliveryItems = (ArrayList<OrderItem>) node.getDeliveryItemList();
					ArrayList<OrderItem> pickupItems = (ArrayList<OrderItem>) node.getPickupItemList();
					if (deliveryItems != null && deliveryItems.size() > 0 && k <= emergencyIndex[idx]) {
						for (OrderItem orderItem : deliveryItems) {
							if (dItemList.isEmpty() || !dItemList.get(0).getId().equals(orderItem.getId())) {
								System.err.println("violate FILO.");
							}
							dItemList.remove(0);
						}
					}
					if (pickupItems != null && pickupItems.size() > 0 && k <= emergencyIndex[idx]) {
						for (OrderItem orderItem : pickupItems) {
							dItemList.add(0, orderItem);
						}
					}
				}
				boolean isDesEmpty = true;
				if (vehicle.getDes() != null && dItemList.size() == 0) {
					isDesEmpty = false;
				}
				int e = emergencyIndex[idx];
				if ((dItemList != null && dItemList.size() > 0) || !isDesEmpty) {
					for (int i = e + 1; i < id2NodeList.size(); i++) {
						Node node = id2NodeList.get(i);
						if (node.getDeliveryItemList() != null && node.getDeliveryItemList().size() > 0) {
							for (OrderItem orderItem : node.getDeliveryItemList()) {
								if (dItemList.contains(orderItem)) {
									dItemList.remove(orderItem);
								}
							}
						}
						if (node.getPickupItemList() != null && node.getPickupItemList().size() > 0) {
							for (OrderItem orderItem : node.getPickupItemList()) {
								dItemList.add(0, orderItem);
							}
						}
						emergencyIndex[idx] = i;
					}
				}

			}
			idx++;
		}
	}

	/**
	 * 根据每条辆车规划的isDelayDispatch的取值情况，决定要不要延迟执行该车辆的派送活动
	 * 并将算法结果Destination按照json的格式写到文件中
	 * 
	 * @param vehicleId2NodeMap
	 */
	private static void writeRouteJson2FileWithDelayTime(LinkedHashMap<String, ArrayList<Node>> vehicleId2NodeMap) {
		JSONObject resultJSONObject = new JSONObject(new LinkedHashMap());
		int index = 0;
		for (Map.Entry<String, ArrayList<Node>> vehicleId2Node : vehicleId2NodeMap.entrySet()) {
			String vehicleId = vehicleId2Node.getKey();
			ArrayList<Node> nodeList = vehicleId2Node.getValue();
			JSONArray vehicleItemArray = new JSONArray();
			if (emergencyIndex[index] > 0) {
				if (null != nodeList && nodeList.size() > 0) {
					for (int i = 0; i < emergencyIndex[index]; i++) {
						Node node = nodeList.get(i);
						JSONObject curNodeJson = new JSONObject();
						JSONArray pickupItemArray = new JSONArray();
						JSONArray deliveryItemArray = new JSONArray();

						if (null != node.getPickupItemList() && 0 != node.getPickupItemList().size()) {
							for (OrderItem item : node.getPickupItemList()) {
								pickupItemArray.add(item.getId());
							}
						}
						if (null != node.getDeliveryItemList() && 0 != node.getDeliveryItemList().size()) {
							for (OrderItem item : node.getDeliveryItemList()) {
								deliveryItemArray.add(item.getId());
							}
						}
						curNodeJson.put("factory_id", node.getId());
						curNodeJson.put("lng", node.getLng());
						curNodeJson.put("lat", node.getLat());
						curNodeJson.put("delivery_item_list", deliveryItemArray);
						curNodeJson.put("pickup_item_list", pickupItemArray);
						curNodeJson.put("arrive_time", node.getArriveTime());
						curNodeJson.put("leave_time", node.getLeaveTime());
						vehicleItemArray.add(curNodeJson);
					}
				}
			}
			index++;
			resultJSONObject.put(vehicleId, vehicleItemArray);
		}
		String outputFile = input_directory + "/" + "output_route.json";
		File f = new File(outputFile);
		if (!f.getParentFile().exists()) {
			f.getParentFile().mkdir();
		}
		try (FileWriter file = new FileWriter(outputFile)) {
			file.write(resultJSONObject.toJSONString());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 根据每条辆车规划的isDelayDispatch的取值情况，决定要不要延迟执行该路径的订单安排
	 * 
	 * @param vehicleId2NodeMap
	 */
	private static void writeDestinationJson2FileWithDelayTime(LinkedHashMap<String, Node> vehicleId2NodeMap) {
		JSONObject resultJSONObject = new JSONObject(new LinkedHashMap());
		int index = 0;
		for (Map.Entry<String, Node> vehicleId2Node : vehicleId2NodeMap.entrySet()) {
			String vehicleId = vehicleId2Node.getKey();
			Node node = vehicleId2Node.getValue();
			JSONObject vehicleItembject = new JSONObject();
			JSONObject curNodeJson = null;
			if (emergencyIndex[index++] > -1 || id2VehicleMap.get(vehicleId).getDes() != null) {
				if (null != node) {
					JSONArray pickupItemArray = new JSONArray();
					JSONArray deliveryItemArray = new JSONArray();
					curNodeJson = new JSONObject();
					if (null != node.getPickupItemList() && 0 != node.getPickupItemList().size()) {
						for (OrderItem item : node.getPickupItemList()) {
							pickupItemArray.add(item.getId());
						}
					}
					if (null != node.getDeliveryItemList() && 0 != node.getDeliveryItemList().size()) {
						for (OrderItem item : node.getDeliveryItemList()) {
							deliveryItemArray.add(item.getId());
						}
					}
					curNodeJson.put("factory_id", node.getId());
					curNodeJson.put("lng", node.getLng());
					curNodeJson.put("lat", node.getLat());
					curNodeJson.put("delivery_item_list", deliveryItemArray);
					curNodeJson.put("pickup_item_list", pickupItemArray);
					curNodeJson.put("arrive_time", node.getArriveTime());
					curNodeJson.put("leave_time", node.getLeaveTime());
				}
			}
			resultJSONObject.put(vehicleId, curNodeJson);
		}
		String outputFile = input_directory + "/" + "output_destination.json";
		File f = new File(outputFile);
		if (!f.getParentFile().exists()) {
			f.getParentFile().mkdir();
		}
		try (FileWriter file = new FileWriter(outputFile)) {
			file.write(resultJSONObject.toJSONString());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 构造Dispatch Algorithm输出Json文件
	 */
	private static void outputJson() {
		getOutputSolution();
		writeDestinationJson2File(vehicleId2Destination);
		writeRouteJson2File(vehicleId2PlannedRoute);
	}

	/**
	 * 将算法求得的路径解vehicleId2PlannedRoute按华为输出解的要求进行处理
	 */
	private static void getOutputSolution() {
		for (Map.Entry<String, Vehicle> id2VehicleItem : id2VehicleMap.entrySet()) {
			String vehicleId = id2VehicleItem.getKey();
			Vehicle vehicle = id2VehicleItem.getValue();
			ArrayList<Node> orignPlannedRoute = vehicleId2PlannedRoute.get(vehicleId);
			ArrayList<Node> plannedRoute = new ArrayList<Node>();
			destination = null;
			if (vehicle.getDes() != null) {
				if (null == orignPlannedRoute || 0 == orignPlannedRoute.size()) {
					System.err.println("Planned route of vehicle " + vehicleId + " is wrong");
				} else {
					destination = orignPlannedRoute.get(0);
					destination.setArriveTime(vehicle.getDes().getArriveTime());
					orignPlannedRoute.remove(0);
				}
				if (destination != null && !vehicle.getDes().getId().equals(destination.getId())) {
					System.err.println("Vehicle " + vehicleId + "returned destination id is" + vehicle.getDes().getId()
							+ "however the origin destination id is" + destination.getId());
				}
			} else if (orignPlannedRoute != null && orignPlannedRoute.size() > 0) {
				destination = orignPlannedRoute.get(0);
				orignPlannedRoute.remove(0);
			}
			if (null != orignPlannedRoute && 0 == orignPlannedRoute.size()) {
				orignPlannedRoute = null;
			}
			vehicleId2Destination.put(vehicleId, destination);
			vehicleId2PlannedRoute.put(vehicleId, orignPlannedRoute);
		}
	}

	/**
	 * 将算法结果Destination按照json的格式写到文件中
	 * 
	 * @param vehicleId2NodeMap
	 */
	private static void writeDestinationJson2File(LinkedHashMap<String, Node> vehicleId2NodeMap) {
		JSONObject resultJSONObject = new JSONObject(new LinkedHashMap());
		for (Map.Entry<String, Node> vehicleId2Node : vehicleId2NodeMap.entrySet()) {
			String vehicleId = vehicleId2Node.getKey();
			Node node = vehicleId2Node.getValue();
			JSONObject vehicleItembject = new JSONObject();
			JSONObject curNodeJson = null;
			if (null != node) {
				JSONArray pickupItemArray = new JSONArray();
				JSONArray deliveryItemArray = new JSONArray();
				curNodeJson = new JSONObject();
				if (null != node.getPickupItemList() && 0 != node.getPickupItemList().size()) {
					for (OrderItem item : node.getPickupItemList()) {
						pickupItemArray.add(item.getId());
					}
				}
				if (null != node.getDeliveryItemList() && 0 != node.getDeliveryItemList().size()) {
					for (OrderItem item : node.getDeliveryItemList()) {
						deliveryItemArray.add(item.getId());
					}
				}
				curNodeJson.put("factory_id", node.getId());
				curNodeJson.put("lng", node.getLng());
				curNodeJson.put("lat", node.getLat());
				curNodeJson.put("delivery_item_list", deliveryItemArray);
				curNodeJson.put("pickup_item_list", pickupItemArray);
				curNodeJson.put("arrive_time", node.getArriveTime());
				curNodeJson.put("leave_time", node.getLeaveTime());
			}
			resultJSONObject.put(vehicleId, curNodeJson);
		}
		String outputFile = input_directory + "/" + "output_destination.json";
		File f = new File(outputFile);
		if (!f.getParentFile().exists()) {
			f.getParentFile().mkdir();
		}
		try (FileWriter file = new FileWriter(outputFile)) {
			file.write(resultJSONObject.toJSONString());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 将算法结果按照json的格式写到文件中
	 * 
	 * @param vehicleId2NodeMap
	 */
	private static void writeRouteJson2File(LinkedHashMap<String, ArrayList<Node>> vehicleId2NodeMap) {
		JSONObject resultJSONObject = new JSONObject(new LinkedHashMap());
		for (Map.Entry<String, ArrayList<Node>> vehicleId2Node : vehicleId2NodeMap.entrySet()) {
			String vehicleId = vehicleId2Node.getKey();
			ArrayList<Node> nodeList = vehicleId2Node.getValue();
			JSONArray vehicleItemArray = new JSONArray();
			if (null != nodeList && nodeList.size() > 0) {
				for (Node node : nodeList) {
					JSONObject curNodeJson = new JSONObject();
					JSONArray pickupItemArray = new JSONArray();
					JSONArray deliveryItemArray = new JSONArray();

					if (null != node.getPickupItemList() && 0 != node.getPickupItemList().size()) {
						for (OrderItem item : node.getPickupItemList()) {
							pickupItemArray.add(item.getId());
						}
					}
					if (null != node.getDeliveryItemList() && 0 != node.getDeliveryItemList().size()) {
						for (OrderItem item : node.getDeliveryItemList()) {
							deliveryItemArray.add(item.getId());
						}
					}
					curNodeJson.put("factory_id", node.getId());
					curNodeJson.put("lng", node.getLng());
					curNodeJson.put("lat", node.getLat());
					curNodeJson.put("delivery_item_list", deliveryItemArray);
					curNodeJson.put("pickup_item_list", pickupItemArray);
					curNodeJson.put("arrive_time", node.getArriveTime());
					curNodeJson.put("leave_time", node.getLeaveTime());
					vehicleItemArray.add(curNodeJson);
				}
			}
			resultJSONObject.put(vehicleId, vehicleItemArray);
		}
		String outputFile = input_directory + "/" + "output_route.json";
		File f = new File(outputFile);
		if (!f.getParentFile().exists()) {
			f.getParentFile().mkdir();
		}
		try (FileWriter file = new FileWriter(outputFile)) {
			file.write(resultJSONObject.toJSONString());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 深度赋值List<Object>
	 * 
	 * @param <T>
	 * @param src
	 * @return
	 * @throws IOException
	 * @throws ClassNotFoundException
	 */
	public static <T> List<T> deepCopy(List<T> src) throws IOException, ClassNotFoundException {
		ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
		ObjectOutputStream out = new ObjectOutputStream(byteOut);
		out.writeObject(src);

		ByteArrayInputStream byteIn = new ByteArrayInputStream(byteOut.toByteArray());
		ObjectInputStream in = new ObjectInputStream(byteIn);
		@SuppressWarnings("unchecked")
		List<T> dest = (List<T>) in.readObject();
		return dest;
	}

}
