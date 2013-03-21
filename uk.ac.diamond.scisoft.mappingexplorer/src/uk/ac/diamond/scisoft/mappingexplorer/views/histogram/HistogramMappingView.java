/*-
 * Copyright © 2011 Diamond Light Source Ltd.
 *
 * This file is part of GDA.
 *
 * GDA is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation.
 *
 * GDA is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along
 * with GDA. If not, see <http://www.gnu.org/licenses/>.
 */
package uk.ac.diamond.scisoft.mappingexplorer.views.histogram;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.dawb.common.ui.plot.AbstractPlottingSystem;
import org.dawb.common.ui.plot.PlotType;
import org.dawb.common.ui.plot.PlottingFactory;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPart2;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.PageBook;
import org.eclipse.ui.part.ViewPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.diamond.scisoft.analysis.dataset.AbstractDataset;
import uk.ac.diamond.scisoft.analysis.dataset.IDataset;
import uk.ac.diamond.scisoft.analysis.dataset.function.Histogram;
import uk.ac.diamond.scisoft.analysis.rcp.editors.HDF5TreeEditor;
import uk.ac.diamond.scisoft.mappingexplorer.views.IDatasetPlotterContainingView;
import uk.ac.diamond.scisoft.mappingexplorer.views.IMappingView2dData;
import uk.ac.diamond.scisoft.mappingexplorer.views.twod.TwoDMappingView;

/**
 * @author rsr31645
 */
public class HistogramMappingView extends ViewPart implements IDatasetPlotterContainingView {

	private static final String LBL_PART_CHANGED = "Histogram displayed from %1$s";
	private static final String DEFAULT_LABEL = "Shows the histogram from a dataset that is provided.";
	public static final String ID = "uk.ac.diamond.scisoft.mappingexplorer.histview";

	private static final int NUM_BINS = 128; // match value used in HistogramView
	private Composite rootComposite;
	private AbstractPlottingSystem plottingSystem;
	private static final Logger logger = LoggerFactory.getLogger(HistogramMappingView.class);
	private Label lblHistogram;

	private PageBook dataSetPlotterPgBook;
	private Composite noDataPage;

	private Composite activePage = null;
	private Composite plotPage;

	public HistogramMappingView() {

	}

	@Override
	public void createPartControl(Composite parent) {
		rootComposite = new Composite(parent, SWT.None);
		GridLayout layout = new GridLayout();
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		layout.verticalSpacing = 0;
		layout.horizontalSpacing = 0;
		rootComposite.setLayout(layout);

		lblHistogram = new Label(rootComposite, SWT.None);
		lblHistogram.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		lblHistogram.setText(DEFAULT_LABEL);
		dataSetPlotterPgBook = new PageBook(rootComposite, SWT.None);
		dataSetPlotterPgBook.setLayoutData(new GridData(GridData.FILL_BOTH));

		noDataPage = new Composite(dataSetPlotterPgBook, SWT.None);
		noDataPage.setLayout(new FillLayout());

		plotPage = new Composite(dataSetPlotterPgBook, SWT.None);
		plotPage.setLayout(new FillLayout());

		try {
			plottingSystem = PlottingFactory.createPlottingSystem();
		} catch (Exception e) {
			logger.error("Unable to create plotting system", e);
		}
		plottingSystem.createPlotPart(plotPage, "HistogramPlotting", getViewSite().getActionBars(),
				PlotType.XY_STACKED, null);

		disablePlottingSystemActions(plottingSystem);
		activePage = noDataPage;
		// The below listeners are listeners to the selection service. A selection change event will trigger these
		// listeners to be invoked. The reason for adding them twice in the way they've been added is because of a
		// problem encountered while adding them only once.

		// To have to listen to changes when the TwoDMapping view loads any new data we need to add the listener using
		// the signature org.eclipse.ui.ISelectionService.addSelectionListener(String, ISelectionListener). This,
		// however, doesn't work for changes within the secondary views that are created for the TwoDMappingView.
		getSite().getWorkbenchWindow().getSelectionService()
				.addSelectionListener(TwoDMappingView.ID, histogramDatasetProviderListener);
		// This is added so that selection of areas on the secondary views are listened to and the histogram for the
		// section is plotted against.
		getSite().getWorkbenchWindow().getSelectionService().addSelectionListener(histogramDatasetProviderListener);
	}

	protected void disablePlottingSystemActions(AbstractPlottingSystem plottingSystem) {
		plottingSystem.getPlotActionSystem().remove("org.dawb.workbench.ui.editors.plotting.swtxy.removeRegions");
		plottingSystem.getPlotActionSystem().remove("org.csstudio.swt.xygraph.toolbar.configureConfigure Settings...");
		plottingSystem.getPlotActionSystem().remove("org.csstudio.swt.xygraph.toolbar.configureShow Legend");
		plottingSystem.getPlotActionSystem().remove("org.dawb.workbench.plotting.histo");
		plottingSystem.getPlotActionSystem().remove("org.csstudio.swt.xygraph.toolbar.configure");
		plottingSystem.getPlotActionSystem().remove("org.dawb.workbench.ui.editors.plotting.swtxy.addRegions");

		plottingSystem.getPlotActionSystem().remove("org.dawb.workbench.plotting.rescale");
		plottingSystem.getPlotActionSystem().remove("org.dawb.workbench.plotting.plotIndex");
		plottingSystem.getPlotActionSystem().remove("org.dawb.workbench.plotting.plotX");
	}

	@Override
	public void dispose() {
		getSite().getWorkbenchWindow().getSelectionService()
				.removeSelectionListener(TwoDMappingView.ID, histogramDatasetProviderListener);
		getSite().getWorkbenchWindow().getSelectionService().removeSelectionListener(histogramDatasetProviderListener);
		super.dispose();
	}

	private ISelectionListener histogramDatasetProviderListener = new ISelectionListener() {

		@SuppressWarnings("rawtypes")
		@Override
		public void selectionChanged(IWorkbenchPart part, ISelection selection) {
			HistogramSelection histSelection = null;
			String partName = null;
			if (part instanceof IWorkbenchPart2) {
				IWorkbenchPart2 wp2 = (IWorkbenchPart2) part;
				partName = wp2.getPartName();
			}
			if (selection instanceof StructuredSelection) {
				StructuredSelection structSel = (StructuredSelection) selection;
				Iterator iterator = structSel.iterator();
				while (iterator.hasNext()) {
					Object object = (Object) iterator.next();
					if (object instanceof HistogramSelection) {
						histSelection = (HistogramSelection) object;
						break;
					}
				}
			}
			if (selection instanceof HistogramSelection) {
				histSelection = (HistogramSelection) selection;
			}
			if (histSelection != null) {
				lblHistogram.setText(String.format(LBL_PART_CHANGED, partName));
				updatePlot(histSelection.getDataset());
			}
		}
	};

	private void updatePlot(final IDataset ds) {
		if (getViewSite().getShell().getDisplay() != null && !getViewSite().getShell().getDisplay().isDisposed()) {
			getViewSite().getShell().getDisplay().asyncExec(new Runnable() {

				@Override
				public void run() {
					if (ds instanceof AbstractDataset) {
						AbstractDataset ds1 = (AbstractDataset) ds;
						int maxValue = ds1.max().intValue();
						int minValue = ds1.min().intValue();

						Histogram histogram = new Histogram(NUM_BINS);

						histogram.setMinMax(minValue, maxValue);
						List<AbstractDataset> evaluated = histogram.value(ds1);
						AbstractDataset evaluatedDs = evaluated.get(0);
						evaluatedDs.setName("HistogramDataSet");

						try {
							plottingSystem.updatePlot1D(null, Arrays.asList(evaluatedDs), new NullProgressMonitor());
							plottingSystem.setTitle("Histogram");
							plottingSystem.autoscaleAxes();
						} catch (Exception e) {
							logger.error("Plotting problem {}", e);
						}
						activePage = plotPage;
						dataSetPlotterPgBook.showPage(activePage);
					}
				}
			});

		}
	}

	@Override
	public void setFocus() {
		// do nothing for now.
	}

	private IPartListener2 partListener = new IPartListener2() {

		@Override
		public void partVisible(IWorkbenchPartReference partRef) {
			// do nothing
		}

		@Override
		public void partOpened(IWorkbenchPartReference partRef) {
			// do nothing
		}

		@Override
		public void partInputChanged(IWorkbenchPartReference partRef) {
			// do nothing
		}

		@Override
		public void partHidden(IWorkbenchPartReference partRef) {
			// do nothing
		}

		@Override
		public void partDeactivated(IWorkbenchPartReference partRef) {
			// do nothing
		}

		@Override
		public void partClosed(IWorkbenchPartReference partRef) {
			// do nothing
		}

		@Override
		public void partBroughtToTop(IWorkbenchPartReference partRef) {
			logger.debug("partBroughtToTop {}", partRef);
			if (partRef instanceof IEditorReference) {
				IEditorReference iEditorReference = (IEditorReference) partRef;
				if (HDF5TreeEditor.ID.equals(iEditorReference.getId())
						|| iEditorReference.getEditor(false).getAdapter(IMappingView2dData.class) != null) {
					// get the twod selection
					TwoDMappingView twoDMappingView = getTwoDMappingView();
					if (twoDMappingView != null) {
						ISelection selection = twoDMappingView.getSite().getSelectionProvider().getSelection();
						HistogramSelection histSelection = null;
						String partName = null;
						if (selection instanceof StructuredSelection) {
							StructuredSelection structSel = (StructuredSelection) selection;
							Iterator iterator = structSel.iterator();
							while (iterator.hasNext()) {
								Object object = (Object) iterator.next();
								if (object instanceof HistogramSelection) {
									histSelection = (HistogramSelection) object;
									break;
								}
							}
						}
						if (selection instanceof HistogramSelection) {
							histSelection = (HistogramSelection) selection;
						}
						if (histSelection != null) {
							lblHistogram.setText(String.format(LBL_PART_CHANGED, partName));
							updatePlot(histSelection.getDataset());
						}
					}

				}

			}
		}

		private TwoDMappingView getTwoDMappingView() {
			IViewReference[] viewReferences = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
					.getViewReferences();
			for (IViewReference iViewReference : viewReferences) {
				if (TwoDMappingView.ID.equals(iViewReference.getId()) && iViewReference.getSecondaryId() == null) {
					return (TwoDMappingView) iViewReference.getPart(false);
				}
			}
			return null;
		}

		@Override
		public void partActivated(IWorkbenchPartReference partRef) {
			logger.debug("partActivated {}", partRef);
		}
	};
}
