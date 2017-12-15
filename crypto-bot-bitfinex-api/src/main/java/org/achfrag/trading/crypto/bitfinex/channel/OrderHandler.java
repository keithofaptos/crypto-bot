package org.achfrag.trading.crypto.bitfinex.channel;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import org.achfrag.trading.crypto.bitfinex.BitfinexApiBroker;
import org.achfrag.trading.crypto.bitfinex.entity.APIException;
import org.achfrag.trading.crypto.bitfinex.entity.BitfinexOrderType;
import org.achfrag.trading.crypto.bitfinex.entity.ExchangeOrder;
import org.achfrag.trading.crypto.bitfinex.entity.ExchangeOrderState;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrderHandler implements ChannelHandler {
	
	/**
	 * The Logger
	 */
	final static Logger logger = LoggerFactory.getLogger(OrderHandler.class);

	@Override
	public void handleChannelData(final BitfinexApiBroker bitfinexApiBroker, final JSONArray jsonArray) 
			throws APIException {

		logger.info("Got order callback {}", jsonArray.toString());
		
		final JSONArray orders = jsonArray.getJSONArray(2);
		
		// No orders active
		if(orders.length() == 0) {
			return;
		}
		
		// Snapshot or update
		if(! (orders.get(0) instanceof JSONArray)) {
			handleOrderCallback(bitfinexApiBroker, orders);
		} else {
			for(int orderPos = 0; orderPos < orders.length(); orderPos++) {
				final JSONArray orderArray = orders.getJSONArray(orderPos);
				handleOrderCallback(bitfinexApiBroker, orderArray);
			}
			
			// All snapshots are completes
			final CountDownLatch orderSnapshotLatch = bitfinexApiBroker.getOrderSnapshotLatch();
			if(orderSnapshotLatch != null) {
				orderSnapshotLatch.countDown();
			}
		}
	}

	/**
	 * Handle a single order callback
	 * @param bitfinexApiBroker 
	 * @param orderArray
	 * @throws APIException 
	 */
	private void handleOrderCallback(BitfinexApiBroker bitfinexApiBroker, final JSONArray order) throws APIException {		
		final ExchangeOrder exchangeOrder = new ExchangeOrder();
		exchangeOrder.setOrderId(order.getLong(0));
		exchangeOrder.setGroupId(order.optInt(1, -1));
		exchangeOrder.setCid(order.getLong(2));
		exchangeOrder.setSymbol(order.getString(3));
		exchangeOrder.setCreated(order.getLong(4));
		exchangeOrder.setUpdated(order.getLong(5));
		exchangeOrder.setAmount(order.getDouble(6));
		exchangeOrder.setAmountAtCreation(order.getDouble(7));
		exchangeOrder.setOrderType(BitfinexOrderType.fromString(order.getString(8)));
		
		final ExchangeOrderState orderState = ExchangeOrderState.fromString(order.getString(13));
		exchangeOrder.setState(orderState);
		
		exchangeOrder.setPrice(order.getDouble(16));
		exchangeOrder.setPriceAvg(order.optDouble(17, -1));
		exchangeOrder.setPriceTrailing(order.optDouble(18, -1));
		exchangeOrder.setPriceAuxLimit(order.optDouble(19, -1));
		exchangeOrder.setNotify(order.getInt(23) == 1 ? true : false);
		exchangeOrder.setHidden(order.getInt(24) == 1 ? true : false);

		final List<ExchangeOrder> orders = bitfinexApiBroker.getOrders();
		
		synchronized (orders) {
			// Replace order 
			orders.removeIf(o -> o.getOrderId() == exchangeOrder.getOrderId());
			
			// Remove canceled orders
			if(exchangeOrder.getState() != ExchangeOrderState.STATE_CANCELED) {
				orders.add(exchangeOrder);
			}
						
			orders.notifyAll();
		}
		
		// Notify callbacks
		final List<Consumer<ExchangeOrder>> orderCallbacks = bitfinexApiBroker.getOrderCallbacks();
		synchronized (orderCallbacks) {
			orderCallbacks.forEach(c -> c.accept(exchangeOrder));
		}
	}
}
