package org.cryptomator.windows.quickaccess;

import org.cryptomator.integrations.common.OperatingSystem;
import org.cryptomator.integrations.common.Priority;
import org.cryptomator.integrations.quickaccess.QuickAccessService;
import org.cryptomator.integrations.quickaccess.QuickAccessServiceException;
import org.cryptomator.windows.common.RegistryKey;
import org.cryptomator.windows.common.WindowsException;
import org.cryptomator.windows.common.WindowsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Implementation of the {@link QuickAccessService} for Windows Explorer
 * <p>
 * Based on a <a href="https://learn.microsoft.com/en-us/windows/win32/shell/integrate-cloud-storage">Microsoft docs example</a>.
 */
@Priority(100)
@OperatingSystem(OperatingSystem.Value.WINDOWS)
public class ExplorerQuickAccessService implements QuickAccessService {

	private static final Logger LOG = LoggerFactory.getLogger(ExplorerQuickAccessService.class);

	@Override
	public QuickAccessEntry add(Path target, String displayName) throws QuickAccessServiceException {
		if (displayName == null) {
			throw new IllegalArgumentException("Parameter 'displayname' must not be null.");
		}
		if (target == null) {
			throw new IllegalArgumentException("Parameter 'target' must not be null.");
		}
		var entryName = "Vault - " + displayName;
		var clsid = "{" + UUID.randomUUID() + "}";
		LOG.debug("Creating navigation pane entry with CLSID {}", clsid);
		//1. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3} /ve /t REG_SZ /d "MyCloudStorageApp" /f
		try (var t = WindowsRegistry.startTransaction()) {
			try (var baseKey = t.createRegKey(RegistryKey.HKEY_CURRENT_USER, "Software\\Classes\\CLSID\\" + clsid, true)) {
				baseKey.setStringValue("", entryName, false);

				//2. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3}\DefaultIcon /ve /t REG_EXPAND_SZ /d %%SystemRoot%%\system32\imageres.dll,-1043 /f
				//TODO: should this be customizable?
				try (var iconKey = t.createRegKey(baseKey, "DefaultIcon", true)) {
					var exePath = ProcessHandle.current().info().command();
					if(exePath.isPresent()) {
						iconKey.setStringValue("", exePath.get(), false);
					} else {
						iconKey.setStringValue("", "%SystemRoot%\\system32\\shell32.dll,4", true); //the regular folder icon
					}
				}

				//3. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3} /v System.IsPinnedToNameSpaceTree /t REG_DWORD /d 0x1 /f
				baseKey.setDwordValue("System.IsPinnedToNameSpaceTree", 0x1);

				//4. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3} /v SortOrderIndex /t REG_DWORD /d 0x42 /f
				baseKey.setDwordValue("SortOrderIndex", 0x41);

				//5. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3}\InProcServer32 /ve /t REG_EXPAND_SZ /d  /f
				try (var inProcServer32Key = t.createRegKey(baseKey, "InProcServer32", true)) {
					inProcServer32Key.setStringValue("", "%systemroot%\\system32\\shell32.dll", true);
				}

				//6. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3}\Instance /v CLSID /t REG_SZ /d {0E5AAE11-A475-4c5b-AB00-C66DE400274E} /f
				try (var instanceKey = t.createRegKey(baseKey, "Instance", true)) {
					instanceKey.setStringValue("CLSID", "{0E5AAE11-A475-4c5b-AB00-C66DE400274E}", false);

					//7. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3}\Instance\InitPropertyBag /v Attributes /t REG_DWORD /d 0x411 /f
					// Attributes are READ_ONLY, DIRECTORY, REPARSE_POINT
					try (var initPropertyBagKey = t.createRegKey(instanceKey, "InitPropertyBag", true)) {
						initPropertyBagKey.setDwordValue("Attributes", 0x411);

						//8. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3}\Instance\InitPropertyBag /v TargetFolderPath /t REG_EXPAND_SZ /d %%USERPROFILE%%\MyCloudStorageApp /f
						initPropertyBagKey.setStringValue("TargetFolderPath", target.toString(), false);
					}
				}

				//9. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3}\ShellFolder /v FolderValueFlags /t REG_DWORD /d 0x28 /f
				try (var shellFolderKey = t.createRegKey(baseKey, "ShellFolder", true)) {
					shellFolderKey.setDwordValue("FolderValueFlags", 0x28);

					//10. reg add HKCU\Software\Classes\CLSID\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3}\ShellFolder /v Attributes /t REG_DWORD /d 0xF080004D /f
					shellFolderKey.setDwordValue("Attributes", 0xF080004D);
				}
				LOG.trace("Created RegKey {} and subkeys, including Values", baseKey);
			}

			//11. reg add HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Desktop\NameSpace\{0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3} /ve /t REG_SZ /d MyCloudStorageApp /f
			var nameSpaceSubKey = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace\\" + clsid;
			try (var nameSpaceKey = t.createRegKey(RegistryKey.HKEY_CURRENT_USER, nameSpaceSubKey, true)) {
				nameSpaceKey.setStringValue("", entryName, false);
				LOG.trace("Created RegKey {} and setting default value", nameSpaceKey);
			}

			//12. reg add HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\HideDesktopIcons\NewStartPanel /v {0672A6D1-A6E0-40FE-AB16-F25BADC6D9E3} /t REG_DWORD /d 0x1 /f
			try (var newStartPanelKey = t.createRegKey(RegistryKey.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\HideDesktopIcons\\NewStartPanel", true)) {
				newStartPanelKey.setDwordValue(clsid, 0x1);
				LOG.trace("Set value {} for RegKey {}", clsid, newStartPanelKey);
			}
			t.commit();
		} catch (WindowsException e) {
			throw new QuickAccessServiceException("Adding entry to Explorer navigation pane via Windows registry failed.", e);
		}
		return new ExplorerQuickAccessEntry(clsid);
	}

	static class ExplorerQuickAccessEntry implements QuickAccessService.QuickAccessEntry {

		private final String clsid;
		private volatile boolean isClosed = false;

		private ExplorerQuickAccessEntry(String clsid) {
			this.clsid = clsid;
		}

		@Override
		public synchronized void remove() throws QuickAccessServiceException {
			if (isClosed) {
				return;
			}

			LOG.debug("Removing navigation pane entry with CLSID {}", clsid);
			try (var t = WindowsRegistry.startTransaction()) {
				//undo step 11.
				var nameSpaceSubkey = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace\\" + clsid;
				LOG.trace("Removing RegKey {}", nameSpaceSubkey);
				t.deleteRegKey(RegistryKey.HKEY_CURRENT_USER, nameSpaceSubkey, true);

				//undo step 12.
				try (var nameSpaceKey = t.openRegKey(RegistryKey.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\HideDesktopIcons\\NewStartPanel")) {
					LOG.trace("Removing Value {} of RegKey {}", clsid, nameSpaceKey);
					nameSpaceKey.deleteValue(clsid, true);
				}

				//undo everything else
				try (var baseKey = t.openRegKey(RegistryKey.HKEY_CURRENT_USER, "Software\\Classes\\CLSID\\" + clsid)) {
					LOG.trace("Wiping everything under RegKey {} and key itself.", baseKey);
					baseKey.deleteAllValuesAndSubtrees();
				}
				t.deleteRegKey(RegistryKey.HKEY_CURRENT_USER, "Software\\Classes\\CLSID\\" + clsid, true);
				t.commit();
				isClosed = true;
			} catch (WindowsException e) {
				throw new QuickAccessServiceException("Removing entry from Explorer navigation pane via Windows registry failed.", e);
			}
		}
	}

}
