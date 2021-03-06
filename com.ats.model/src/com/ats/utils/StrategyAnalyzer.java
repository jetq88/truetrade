package com.ats.utils;

import java.util.Collection;

import org.eclipse.jface.preference.PreferenceStore;

import com.ats.engine.TradeSummary;

public class StrategyAnalyzer {

	public static TradeStats calculateTradeStats(Collection <TradeSummary> trades) {
//		if( trades == null || trades.size() <= 0) {
//			return null;
//		}
		TradeStats stats = new TradeStats();
		
		double peak = -99999; //to calculate drawdown
	
		final PreferenceStore preferenceStore = Utils.getPreferenceStore();
		
		for(TradeSummary ts : trades) {
			stats.maxShares = Math.max(ts.getTotalBuyQty(), stats.maxShares);
			stats.maxShares = Math.max(ts.getTotalSellQty(), stats.maxShares);
			stats.maxPerTradeLFT = Math.max(stats.maxPerTradeLFT, ts.getLossFromTop());
			stats.numTrades++;
			stats.numShares += (ts.getTotalBuyQty() + ts.getTotalSellQty());
			stats.grossUnrealized += ts.getUnrealizedProfit();
			
			// calc comission
			if( preferenceStore.getBoolean(Utils.COMMISSION_SHARE) ) {
				stats.commissions += stats.numShares * preferenceStore.getDouble(Utils.COMMISSION_SHARE_VALUE);
			} else if( preferenceStore.getBoolean(Utils.COMMISSION_ORDER) ) {
				stats.commissions += stats.numTrades * preferenceStore.getDouble(Utils.COMMISSION_ORDER_VALUE);
			} else if (preferenceStore.getBoolean(Utils.COMMISSION_TRANS) ) {
				stats.commissions += (ts.getAvgBuyPrice() * ts.getTotalBuyQty() + ts.getAvgSellPrice() * ts.getTotalSellQty()) * preferenceStore.getDouble(Utils.COMMISSION_TRANS_VALUE) / 100;
			} 
			
			if( ts.getRealizedPnL() > 0 ) {
				stats.grossProfit += ts.getRealizedPnL();
				stats.netProfit += ts.getRealizedNetPnL();
				stats.numWinners++;
				stats.largestWinner = Math.max(ts.getRealizedNetPnL(), stats.largestWinner);
				stats.numConsecLosers = 0;
				stats.numConsecWinners++;
				stats.maxConsecWinners = Math.max(stats.maxConsecWinners, stats.numConsecWinners);
			} else {
				stats.grossLoss += ts.getRealizedPnL();
				stats.netLoss += ts.getRealizedNetPnL();
				stats.numLosers++;
				stats.largestLoser = Math.min(ts.getRealizedNetPnL(), stats.largestLoser);
				stats.numConsecWinners = 0;
				stats.numConsecLosers++;
				stats.maxConsecLosers = Math.max(stats.maxConsecLosers, stats.numConsecLosers);
			}
			
			stats.profitFactor = (stats.grossLoss != 0) ? (stats.grossProfit / stats.grossLoss) : 0;
			stats.equityHigh = Math.max(stats.equityHigh, stats.getTotalNet());
			
			//calculating drawdown
			if (stats.getTotalNet() > peak) {
				peak = stats.getTotalNet();
			} else {
				double curDD = 100.0 * (peak - stats.getTotalNet()) / peak;
				if (curDD > stats.maxDrawdown)
					stats.maxDrawdown = curDD;
			}
			//stats.maxDrawdown = Math.min(stats.maxDrawdown, stats.equityHigh - stats.getTotalNet());
		}
		
		return stats;
		
	}

}
