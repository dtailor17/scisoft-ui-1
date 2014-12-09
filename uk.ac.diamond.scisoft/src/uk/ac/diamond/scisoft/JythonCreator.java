/*
 * Copyright (c) 2012 Diamond Light Source Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package uk.ac.diamond.scisoft;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.dawnsci.analysis.api.io.ILoaderFactoryExtensionService;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.python.copiedfromeclipsesrc.JavaVmLocationFinder;
import org.python.pydev.core.IInterpreterInfo;
import org.python.pydev.core.IInterpreterManager;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.MisconfigurationException;
import org.python.pydev.debug.newconsole.PydevConsoleConstants;
import org.python.pydev.editor.codecompletion.revisited.ModulesManagerWithBuild;
import org.python.pydev.plugin.PydevPlugin;
import org.python.pydev.plugin.preferences.PydevPrefs;
import org.python.pydev.runners.SimpleRunner;
import org.python.pydev.shared_core.io.FileUtils;
import org.python.pydev.shared_core.structure.Tuple;
import org.python.pydev.ui.interpreters.JythonInterpreterManager;
import org.python.pydev.ui.pythonpathconf.InterpreterInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.diamond.jython.util.JythonPath;

public class JythonCreator implements IStartup {

	private static Logger logger = LoggerFactory.getLogger(JythonCreator.class);

	@Override
	public void earlyStartup() {
		try {
			initialiseConsole();
			initialiseInterpreter(new NullProgressMonitor());
		} catch (Throwable e) {
			logger.error("Cannot create interpreter!", e);
		}
	}

	private void initialiseConsole() {
		// need to set some preferences to get the Pydev features working.
		IPreferenceStore pydevDebugPreferenceStore =  new ScopedPreferenceStore(InstanceScope.INSTANCE,"org.python.pydev.debug");

		pydevDebugPreferenceStore.setDefault(PydevConsoleConstants.INITIAL_INTERPRETER_CMDS, "#Configuring Environment, please wait\nimport scisoftpy as dnp;import sys;sys.executable=''\n");
		pydevDebugPreferenceStore.setDefault(PydevConsoleConstants.INTERACTIVE_CONSOLE_VM_ARGS, "-Xmx512m");
		pydevDebugPreferenceStore.setDefault(PydevConsoleConstants.INTERACTIVE_CONSOLE_MAXIMUM_CONNECTION_ATTEMPTS, 4000);
	}


	private static final String JYTHON_VERSION = "2.5";

	/**
	 * Name of interpreter that is set in the PyDev Jython Interpreter settings
	 */
	public static final String INTERPRETER_NAME = "Jython" + JYTHON_VERSION;

	/**
	 * Boolean to set to true if running jython scripts that utilise ScisoftPy in IDE
	 */
	public static final String RUN_IN_ECLIPSE = "run.in.eclipse";

	private static final String[] removedLibEndings = {
		"pysrc",
		"classpath__" // includes __classpath__ and __pyclasspath__
	};

	private static Set<String> extraPlugins = null;

	private void initialiseInterpreter(IProgressMonitor monitor) throws Exception {
		/*
		 * The layout of plugins can vary between where a built product and
		 * a product run from Ellipse:
		 * 
		 *  1) Built product
		 *     . this class in plugins/a.b.c
		 *     . flat hierarchy with jars and expanded bundles (with jars in a.b.c and a.b.c/jars)
		 *  2) Ellipse run
		 *     . flagged by RUN_IN_ECLIPSE property
		 *     . source code can be in workspace/plugins or workspace_git (this class is in workspace_git/blah.git/a.b.c)
		 * 
		 * Jython lives in diamond-jython.git in uk.ac.diamond.jython (after being moved from uk.ac.gda.libs)
		 */

		logger.debug("Initialising the Jython interpreter setup");

		boolean isRunningInEclipse = "true".equalsIgnoreCase(System.getProperty(RUN_IN_ECLIPSE));

		// Horrible Hack warning: This code is copied from parts of Pydev to set up the interpreter and save it.
		{

			File pluginsDir = JythonPath.getPluginsDirectory(isRunningInEclipse); // plugins or git workspace directory
			if (pluginsDir == null) {
				logger.error("Failed to find plugins directory!");
				return;
			}
			logger.debug("Plugins directory is {}", pluginsDir);

			// Set cache directory to something not in the installation directory
			IPreferenceStore pyStore = PydevPrefs.getPreferenceStore();
			String cachePath = pyStore.getString(IInterpreterManager.JYTHON_CACHE_DIR);
			if (cachePath == null || cachePath.length() == 0) {
				final String workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
				final File cacheDir = new File(workspace, ".jython_cachedir");
				if (!cacheDir.exists())
					cacheDir.mkdirs();
				cachePath = cacheDir.getAbsolutePath();
				pyStore.setValue(IInterpreterManager.JYTHON_CACHE_DIR, cacheDir.getAbsolutePath());
			}
			System.setProperty("python.cachedir", cachePath);

			// check for the existence of this standard pydev script
			final File script = PydevPlugin.getScriptWithinPySrc("interpreterInfo.py");
			if (!script.exists()) {
				logger.error("The file specified does not exist: {} ", script);
				throw new RuntimeException("The file specified does not exist: " + script);
			}
			logger.debug("Script path = {}", script.getAbsolutePath());

			File java = JavaVmLocationFinder.findDefaultJavaExecutable();
			logger.debug("Using java: {}", java);
			String javaPath;
			try {
				javaPath = java.getCanonicalPath();
			} catch (IOException e) {
				logger.warn("Could not resolve default Java path so resorting to PATH", e);
				javaPath = "java";
			}

			String executable = new File(JythonPath.getInterpreterDirectory(), JythonPath.getJythonExecutableName()).getAbsolutePath();
			if (!(new File(executable)).exists()) { 
				logger.error("Failed to find jython jar at all");
				return;
			}
			logger.debug("executable path = {}", executable);

			String[] cmdarray = {javaPath, "-Xmx64m",
//					"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:8000",
					"-Dpython.cachedir.skip=true", // this works in Windows
					"-jar", executable,
					FileUtils.getFileAbsolutePath(script)};
			File workingDir = new File(System.getProperty("java.io.tmpdir"));
//			logger.debug("Cache and working dirs are {} and {}", cachePath, workingDir);
			IPythonNature nature = null;

			String outputString = "";
			try {
				Tuple<Process, String> outTuple = new SimpleRunner().run(cmdarray, workingDir, nature, monitor);
				outputString = IOUtils.toString(outTuple.o1.getInputStream());
			} catch (IOException e1) {
				logger.error("Could not parse output from running interpreterInfo.py in Jython", e1);
			} catch (Exception e2) {
				logger.error("Something went wrong in running interpreterInfo.py in Jython", e2);
			}

			logger.debug("Output String is {}", outputString);

			// this is the main info object which contains the environment data
			InterpreterInfo info = null;

			try {
				// HACK Otherwise Pydev shows a dialog to the user.
				ModulesManagerWithBuild.IN_TESTS = true;
				info = InterpreterInfo.fromString(outputString, false);
			} catch (Exception e) {
				logger.error("InterpreterInfo.fromString(outTup.o1) has failed in pydev setup with exception");
				logger.error("{}", e);

			} finally {
				ModulesManagerWithBuild.IN_TESTS = false;
			}

			if (info == null) {
				logger.error("pydev info is set to null");
				return;
			}

			// the executable is the jar itself
			info.executableOrJar = executable;

			final String osName = System.getProperty("os.name");
			final boolean isMacOSX = osName.contains("Mac OS X");
			final String pathEnv = isMacOSX ? "DYLD_LIBRARY_PATH" : (osName.contains("Windows") ? "PATH"
					: "LD_LIBRARY_PATH");
			logPaths("Library paths:", System.getenv(pathEnv));

			logPaths("Class paths:", System.getProperty("java.library.path"));

			// set of python paths
			Set<String> pyPaths = new TreeSet<String>();

			// we have to find the jars before we restore the compiled libs
			final List<File> jars = JavaVmLocationFinder.findDefaultJavaJars();
			for (File jar : jars) {
				if (!pyPaths.add(jar.getAbsolutePath())) {
					logger.warn("File {} already there!", jar.getName());
				}
			}

			// Find all packages that contribute to loader factory
			ILoaderFactoryExtensionService service = (ILoaderFactoryExtensionService) Activator.getService(ILoaderFactoryExtensionService.class);
			if (service != null) {
				List<String> plugins = service.getPlugins();
				logger.debug("Extra plugins: {}", plugins);
				extraPlugins = new HashSet<String>(plugins);
			}

			//Get Jython paths for DAWN libs
			pyPaths.addAll(JythonPath.assembleJyPaths(pluginsDir, extraPlugins, isRunningInEclipse));
			//Also need allPluginsDirs for later parts
			final List<File> allPluginDirs = JythonPath.findDirs(pluginsDir, extraPlugins, isRunningInEclipse);

			Set<String> removals = new HashSet<String>();
			for (String s : info.libs) {
				String ls = s.toLowerCase();
				for (String r : removedLibEndings) {
					if (ls.endsWith(r)) {
						removals.add(s);
						break;
					}
				}
			}
			info.libs.removeAll(removals);
			info.libs.addAll(pyPaths);

			// now set up the dynamic library environment
			File libraryDir = new File(pluginsDir.getParent(), "lib");
			Set<String> paths = new LinkedHashSet<String>();
			if (!isRunningInEclipse && libraryDir.exists()) {
				paths.add(libraryDir.getAbsolutePath());
			} else {
				// check each plugin directory's for dynamic libraries
				String osarch = Platform.getOS() + "-" + Platform.getOSArch();
				logger.debug("Using OS and ARCH: {}", osarch);
				for (File dir : allPluginDirs) {
					File d = new File(dir, "lib");
					if (d.isDirectory()) {
						d = new File(d, osarch);
						if (d.isDirectory()) {
							if (paths.add(d.getAbsolutePath()))
								logger.debug("Adding library path: {}", d);
						}
					}
				}

			}

			// add from environment variables
			String ldPath = System.getenv(pathEnv);
			if (ldPath != null) {
				for (String p : ldPath.split(File.pathSeparator)) {
					paths.add(p);
				}
			}
			StringBuilder allPaths = new StringBuilder();
			for (String p : paths) {
				allPaths.append(p);
				allPaths.append(File.pathSeparatorChar);
			}
			String libraryPath = allPaths.length() > 0 ? allPaths.substring(0, allPaths.length()-1) : null;

			PyDevAdditionalInterpreterSettings settings = new PyDevAdditionalInterpreterSettings();
			Collection<String> envVariables = settings.getAdditionalEnvVariables();
			if (libraryPath == null) {
				logger.warn("{} not defined as no library paths were found!" + pathEnv);
			} else {
				logPaths("Setting " + pathEnv + " for dynamic libraries", libraryPath);
				envVariables.add(pathEnv + "=" + libraryPath);
			}

			if (isMacOSX) {
				// do we also add DYLD_VERSIONED_LIBRARY_PATH and DYLD_ROOT_PATH?
				String fbPathEnv = "DYLD_FALLBACK_LIBRARY_PATH";
				String fbPath = System.getenv(fbPathEnv);
				if (fbPath == null) {
					logger.debug("{} not defined" + fbPathEnv);
				} else {
					logPaths("For Mac OS X, setting " + fbPathEnv + " for dynamic libraries", fbPath);
					envVariables.add(fbPathEnv + "=" + fbPath);
				}
			}

			String[] envVarsAlreadyIn = info.getEnvVariables();
			if (envVarsAlreadyIn != null) {
				envVariables.addAll(Arrays.asList(envVarsAlreadyIn));
			}

			// add custom loader extensions to work around Jython not being OSGI
			if (service != null) {
				String ev = "LOADER_FACTORY_EXTENSIONS=";
				for (String e : service.getExtensions()) {
					ev += e + "|";
				}
				envVariables.add(ev);
			}

			info.setEnvVariables(envVariables.toArray(new String[envVariables.size()]));

			// java, java.lang, etc should be found now
			info.restoreCompiledLibs(monitor);
			info.setName(INTERPRETER_NAME);

			logger.debug("Finalising the Jython interpreter manager");

			final JythonInterpreterManager man = (JythonInterpreterManager) PydevPlugin.getJythonInterpreterManager();
			HashSet<String> set = new HashSet<String>();
			// Note, despite argument in PyDev being called interpreterNamesToRestore
			// in this context that name is the exe. 
			// Pydev doesn't allow two different interpreters to be configured for the same
			// executable path so in some contexts the executable is the unique identifier (as it is here)
			set.add(executable);
			
			// Attempt to update existing Jython configuration
			IInterpreterInfo[] interpreterInfos = man.getInterpreterInfos();
			IInterpreterInfo existingInfo = null;
			try {
				existingInfo = man.getInterpreterInfo(executable, monitor);
			} catch (MisconfigurationException e) {
				// MisconfigurationException thrown if executable not found
			}

			if (existingInfo != null && existingInfo.toString().equals(info.toString())) {
				logger.debug("Jython interpreter already exists with exact settings");
			} else {
				// prune existing interpreters with same name
				Map<String, IInterpreterInfo> infoMap = new LinkedHashMap<String, IInterpreterInfo>();
				for (IInterpreterInfo i : interpreterInfos) {
					infoMap.put(i.getName(), i);
				}
				if (existingInfo == null) {
					if (infoMap.containsKey(INTERPRETER_NAME)) {
						existingInfo = infoMap.get(INTERPRETER_NAME);
						logger.debug("Found interpreter of same name");
					}
				}
				if (existingInfo == null) {
					logger.debug("Adding interpreter as an additional interpreter");
				} else {
					logger.debug("Updating interpreter which was previously created");
				}
				infoMap.put(INTERPRETER_NAME, info);
				try {
					IInterpreterInfo[] infos = new IInterpreterInfo[infoMap.size()];
					int j = 0;
					for (String i : infoMap.keySet()) {
						infos[j++] = infoMap.get(i);
					}
					man.setInfos(infos, set, monitor);
				} catch (RuntimeException e) {
					logger.warn("Problem with restoring info");
				}
			}

			logger.debug("Finished the Jython interpreter setup");
		}
	}
	

	private static void logPaths(String pathname, String paths) {
		if (paths == null)
			return;
		logger.debug(pathname);
		for (String p : paths.split(File.pathSeparator))
			logger.debug("\t{}", p);
	}


}
