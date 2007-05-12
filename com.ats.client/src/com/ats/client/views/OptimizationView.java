package com.ats.client.views;

import org.eclipse.ui.part.ViewPart;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

import com.ats.client.wizards.OptimizeWizard;
import com.ats.client.wizards.ParamValues;
import com.ats.engine.BacktestFactory;
import com.ats.engine.BacktestListener;
import com.ats.engine.PositionManager;
import com.ats.engine.StrategyDefinition;
import com.ats.utils.StrategyAnalyzer;
import com.ats.utils.TradeStats;
import com.ats.utils.Utils;

public class OptimizationView extends ViewPart {
	private static final Logger logger = Logger.getLogger(OptimizationView.class);
	
	public static final String ID = "com.ats.client.views.optimizationView";

	/**
	 * A fun little hack for displaying the columns but dodging the SWT requirement to
	 * use Strings.  This lets you use a switch() block in the labels and dynamically
	 * re-assign the columns at runtime.
	 */
	enum ColType {
		netProfit("Net Profit"),
		commissions("Commissions"),
		trades("Trades"),
		maxDrawdown("Max Drawdown"),
		winners("#Winners"),
		losers("#Losers"),
		avgTrade("Avg Trade"),
		param("Param");
		
		String name;
		private ColType(String name) {
			this.name=name;
		}
	}
	
	private TableViewer viewer;
	private List<ParamValues> paramValues;
	private StrategyDefinition stratDef;
	private List<OptTrial> trials = new ArrayList<OptTrial>();
	private int maxTrials;
	
	public OptimizationView() {
		super();
	}
	@Override
	public void createPartControl(Composite parent) {

		Composite content = new Composite(parent, SWT.NONE);

		GridLayout contentLayout = new GridLayout();
		contentLayout.numColumns = 2;
		content.setLayout(contentLayout);
		
		viewer = new TableViewer(content, SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION );
        viewer.setContentProvider(new OptExecContentProvider());
        viewer.setLabelProvider(new OptExecLabelProvider());
        viewer.setInput(trials);
        
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData gdata = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1);
        gdata.heightHint = 350;
        gdata.widthHint = 350;
        table.setLayoutData(gdata);
        
        for(ColType colType : ColType.values() ) {
        	if( colType == ColType.param ) {
        		// special case
        		continue;
        	}
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(colType.name);
            column.setWidth(50);
            viewer.setData(colType.name, colType);
        }

		launchOptimizer();
	}

	@Override
	public void setFocus() {
	}
	
	
	public void setStrategyDefinition(StrategyDefinition stratDef, List<ParamValues> values) {
		this.stratDef = stratDef;
		this.paramValues = values;
		
		maxTrials = 1;
		for(ParamValues val : values) {
			maxTrials *= val.numTrials;
		}
		trials = new ArrayList<OptTrial>(maxTrials);
		viewer.setInput(trials);
		
		for( ParamValues val : paramValues ) {
	        TableColumn column = new TableColumn(viewer.getTable(), SWT.LEFT);
	        column.setText(val.paramName);
	        column.setWidth(50);
            viewer.setData(val.paramName, ColType.param);
		}
		
	}
	
	private void launchOptimizer() {
		OptimizeWizard wiz = new OptimizeWizard();
		WizardDialog dlg = new WizardDialog(getSite().getShell(), wiz);
		if( dlg.open() == WizardDialog.OK ) {
			setStrategyDefinition(wiz.getStrategyDefinition(), wiz.getParamValues());
	
			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					try {
						new ProgressMonitorDialog(getSite().getShell()).run(true, true,
								new IRunnableWithProgress() {
									public void run(IProgressMonitor monitor) {
										monitor.beginTask("Optimizing...", trials.size());
										runStrategy(monitor);
										while (true) {
											try {
												// unsophisticated means of monitoring, I know.  I'm
												// tired and this works.
												Thread.sleep(1000);
											} catch (InterruptedException e) {
											}
											if (monitor.isCanceled()
													|| trials.size() >= maxTrials) {
												break;
											}
										}
									}
								});
					} catch (Exception e) {
						logger.error("Could not load progress monitor", e);
					} 
				}
			});
			}

	}
	
	private synchronized void runStrategy(final IProgressMonitor monitor) {
		if( trials.size() >= maxTrials ) {
			monitor.done();
			return;
		}
		if( monitor.isCanceled() ) {
			return;
		}
		
		int currTrial = trials.size();
		logger.debug("Running optimizer trial #" + currTrial);
		monitor.setTaskName("Trial " + (currTrial+1) + " of " + maxTrials + "...");
		
		// need to translate the number of trials to a specific permutation
		// of an arbitrarily large number of parameters.
		//
		// emulate a bit set, or a base(m,n) number
		// for param A...Z  with values A1...AN
		// currTrial = A + AN*B + AN*BN*C + ...
		// so A = size % AN
		//    B = (size / AN) % BN
		//    ...
		
		final Map<String, Number> params = new HashMap<String, Number>();
		for(ParamValues vals : paramValues) {
			int delta = currTrial % vals.numTrials;
			if( vals.initVal instanceof Integer ) {
				int val = vals.start.intValue() + (delta * vals.stepSize.intValue());
				params.put(vals.paramName, val);
			} else {
				// double
				double val = vals.start.doubleValue() + (delta * vals.stepSize.doubleValue());
				params.put(vals.paramName, val);
			}
			stratDef.setParameter(vals.paramName, params.get(vals.paramName));
			currTrial = (currTrial - delta)/vals.numTrials;
		}
		logger.debug("Running with params = " + params);
		
		new BacktestFactory().runBacktest(stratDef, new BacktestListener(){
			public void testComplete() {
				monitor.worked(1);
				TradeStats stats = StrategyAnalyzer.calculateTradeStats(PositionManager.getInstance().getAllTrades());
				final OptTrial trial = new OptTrial();
				trial.stats = stats;
				trial.paramVals = params;
				trials.add(trial);
				Display.getDefault().asyncExec(new Runnable() {
					public void run() {
						viewer.add(trial);
					}
				});
				runStrategy(monitor);
			}
		});
	}

	class OptExecLabelProvider implements ITableLabelProvider {
		public Image getColumnImage(Object element, int columnIndex) {
			return null;
		}
		public String getColumnText(Object element, int columnIndex) {
			OptTrial trial = (OptTrial)element;
			
			String colText = viewer.getTable().getColumn(columnIndex).getText();
			ColType type = (ColType)viewer.getData(colText);
			
			String ret = null;
			switch(type) {
			case avgTrade:
				ret = "";
				break;
			case commissions:
				//ret = "" + trial.stats.commissions;
				break;
			case losers:
				ret = "" + trial.stats.numLosers;
				break;
			case winners:
				ret = "" + trial.stats.numWinners;
				break;
			case netProfit:
				ret = Utils.currencyForm.format(trial.stats.getTotalNet());
				break;
			case trades:
				ret = "" + trial.stats.numTrades;;
				break;
			case maxDrawdown:
				ret = Utils.currencyForm.format(trial.stats.maxDrawdown);
				break;
			case param:
				try {
					ret = trial.paramVals.get(colText).toString();
				} catch( Exception e) {
					ret = "";
				}
				break;
			}
			
			return ret;
		}
		public void addListener(ILabelProviderListener listener) {
		}
		public void dispose() {
		}
		public boolean isLabelProperty(Object element, String property) {
			return false;
		}
		public void removeListener(ILabelProviderListener listener) {
		}
	}

}

class OptTrial {
	public Map<String, Number> paramVals = new HashMap<String, Number>();
	public TradeStats stats;
}

class OptExecContentProvider implements IStructuredContentProvider {
	public Object[] getElements(Object inputElement) {
		return ((List)inputElement).toArray();
	}
	public void dispose() {
	}
	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
	}
}
