/*
 * Copyright 2012 Diamond Light Source Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.ac.diamond.scisoft.analysis.rcp.results.navigator.actions;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.ui.PlatformUI;

import uk.ac.diamond.scisoft.analysis.rcp.results.navigator.DataNavigator;

/**
 * This class currently asks the user for a different root file to 
 * set as the file for the nexus tree.
 */
public class SwitchDataSourceAction extends AbstractHandler {


	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		
		final DirectoryDialog dialog = new DirectoryDialog(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), SWT.OPEN);
		dialog.setFilterPath(System.getProperty("gda.data"));
		final String          path   = dialog.open();
		if (path!=null) {
			final DataNavigator view = (DataNavigator)PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().findView(DataNavigator.ID);
			if (view == null) return Boolean.FALSE;
            view.setSelectedPath(path);
			return Boolean.TRUE;
		}
		return Boolean.FALSE;
	}

}
