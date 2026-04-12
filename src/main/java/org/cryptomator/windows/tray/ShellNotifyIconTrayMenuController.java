package org.cryptomator.windows.tray;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import org.cryptomator.integrations.common.CheckAvailability;
import org.cryptomator.integrations.common.OperatingSystem;
import org.cryptomator.integrations.common.Priority;
import org.cryptomator.integrations.tray.ActionItem;
import org.cryptomator.integrations.tray.SeparatorItem;
import org.cryptomator.integrations.tray.SubMenuItem;
import org.cryptomator.integrations.tray.TrayIconLoader;
import org.cryptomator.integrations.tray.TrayMenuController;
import org.cryptomator.integrations.tray.TrayMenuException;
import org.cryptomator.integrations.tray.TrayMenuItem;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Priority(1000)
@CheckAvailability
@OperatingSystem(OperatingSystem.Value.WINDOWS)
public class ShellNotifyIconTrayMenuController implements TrayMenuController {

	private static final String WINDOW_CLASS_NAME = "CryptomatorTrayWindow";
	private static final String WINDOW_TITLE = "CryptomatorTrayWindow";

	private static final int MF_STRING    = 0x00000000;
	private static final int MF_GRAYED    = 0x00000001;
	private static final int MF_SEPARATOR = 0x00000800;
	private static final int MF_POPUP     = 0x00000010;

	private static final int WM_APP = 0x8000;
	private static final int WM_COMMAND = 0x0111;
	private static final int WM_LBUTTONDBLCLK = 0x0203;
	private static final int WM_RBUTTONUP = 0x0205;
	private static final int WM_CONTEXTMENU = 0x007B;
	private static final int WM_NULL = 0x0000;

	private static final int WMAPP_NOTIFYCALLBACK = WM_APP + 1;
	private static final int TRAY_ICON_UID = 1;

	private static final int IMAGE_ICON = 1;
	private static final int LR_LOADFROMFILE = 0x0010;
	private static final int LR_DEFAULTSIZE = 0x0040;

	private static final int TPM_LEFTALIGN = 0x0000;
	private static final int TPM_BOTTOMALIGN = 0x0020;
	private static final int TPM_RIGHTBUTTON = 0x0002;

	private final User32 user32 = User32.INSTANCE;
	private final User32Ext user32Ext = User32Ext.INSTANCE;
	private final Shell32Ext shell32Ext = Shell32Ext.INSTANCE;
	private final Kernel32 kernel32 = Kernel32.INSTANCE;

	private WinDef.HWND hwnd;
	private WinDef.HICON trayIconHandle;
	private WinDef.HMENU rootMenu;
	private Runnable defaultAction = () -> {};
	private Runnable beforeOpenMenu = () -> {};
	private final Map<Integer, Runnable> commandHandlers = new HashMap<>();
	private int nextCommandId = 1000;

	private final WinUser.WindowProc windowProc = (hWnd, uMsg, wParam, lParam) -> {
		switch (uMsg) {
			case WMAPP_NOTIFYCALLBACK:
				return handleTrayCallback(hWnd, wParam, lParam);
			case WM_COMMAND: {
				int commandId = wParam.intValue() & 0xFFFF;
				Runnable action = commandHandlers.get(commandId);
				if (action != null) {
					action.run();
					return new WinDef.LRESULT(0);
				}
				break;
			}
			case WinUser.WM_DESTROY:
				user32.PostQuitMessage(0);
				return new WinDef.LRESULT(0);
			default:
				break;
		}
		return user32.DefWindowProc(hWnd, uMsg, wParam, lParam);
	};

	@CheckAvailability
	public static boolean isAvailable() {
		return isWindows() && isJnaAvailable();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	private static boolean isJnaAvailable() {
		try {
			com.sun.jna.platform.win32.User32.INSTANCE.GetDesktopWindow();
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public void showTrayIcon(Consumer<TrayIconLoader> iconLoader, Runnable defaultAction, String tooltip) throws TrayMenuException {
		this.defaultAction = defaultAction != null ? defaultAction : () -> {};
		this.hwnd = createMessageWindow();

		this.rootMenu = user32Ext.CreatePopupMenu();
		if (this.rootMenu == null) {
			throw new TrayMenuException("Failed to create popup menu.");
		}

		TrayIconLoader.WindowsIcoPath callback = this::showTrayIconWithIcoPath;
		iconLoader.accept(callback);

		Shell32Ext.NOTIFYICONDATA nid = createNotifyIconData(tooltip);
		nid.uFlags = Shell32Ext.NIF_MESSAGE | Shell32Ext.NIF_ICON | Shell32Ext.NIF_TIP | Shell32Ext.NIF_GUID;
		nid.uCallbackMessage = WMAPP_NOTIFYCALLBACK;
		nid.hIcon = trayIconHandle;
		setTooltip(nid, tooltip);
		nid.write();

		if (!shell32Ext.Shell_NotifyIconW(Shell32Ext.NIM_ADD, nid)) {
			throw new TrayMenuException("Failed to add tray icon. Error=" + kernel32.GetLastError());
		}

		Shell32Ext.NOTIFYICONDATA versionData = createNotifyIconData(null);
		versionData.uVersion = Shell32Ext.NOTIFYICON_VERSION_4;
		versionData.write();
		shell32Ext.Shell_NotifyIconW(Shell32Ext.NIM_SETVERSION, versionData);
	}

	private void showTrayIconWithIcoPath(String icoPath) {
		trayIconHandle = loadIcon(icoPath);
	}

	@Override
	public void updateTrayIcon(Consumer<TrayIconLoader> iconLoader) {
		TrayIconLoader.WindowsIcoPath callback = this::updateTrayIconWithIcoPath;
		iconLoader.accept(callback);
	}

	private void updateTrayIconWithIcoPath(String icoPath) {
		checkState(hwnd != null, "Tray icon is not setup. Call showTrayIcon(...) first.");

		if (trayIconHandle != null) {
			user32.DestroyIcon(trayIconHandle);
		}
		trayIconHandle = loadIcon(icoPath);

		Shell32Ext.NOTIFYICONDATA nid = createNotifyIconData(null);
		nid.uFlags = Shell32Ext.NIF_ICON | Shell32Ext.NIF_GUID;
		nid.hIcon = trayIconHandle;
		nid.write();

		shell32Ext.Shell_NotifyIconW(Shell32Ext.NIM_MODIFY, nid);
	}

	@Override
	public void updateTrayMenu(List<TrayMenuItem> items) throws TrayMenuException {
		checkState(hwnd != null, "Tray icon is not setup. Call showTrayIcon(...) first.");

		if (rootMenu != null) {
			user32Ext.DestroyMenu(rootMenu);
		}
		rootMenu = user32Ext.CreatePopupMenu();
		if (rootMenu == null) {
			throw new TrayMenuException("Failed to recreate popup menu.");
		}

		commandHandlers.clear();
		nextCommandId = 1000;
		addChildren(rootMenu, items);
	}

	@Override
	public void onBeforeOpenMenu(Runnable runnable) {
		beforeOpenMenu = runnable != null ? runnable : () -> {};
	}

	private void addChildren(WinDef.HMENU menu, List<TrayMenuItem> items) throws TrayMenuException {
		for (TrayMenuItem item : items) {
			switch (item) {
				case ActionItem a -> {
					int commandId = nextCommandId++;
					commandHandlers.put(commandId, a.action());
					boolean ok = user32Ext.AppendMenuW(
							menu,
							MF_STRING | (a.enabled() ? 0 : MF_GRAYED),
							new WinDef.UINT_PTR(commandId),
							a.title()
					);
					if (!ok) {
						throw new TrayMenuException("Failed to append action item: " + a.title());
					}
				}
				case SeparatorItem ignored -> {
					boolean ok = user32Ext.AppendMenuW(
							menu,
							MF_SEPARATOR,
							new WinDef.UINT_PTR(0),
							null
					);
					if (!ok) {
						throw new TrayMenuException("Failed to append separator.");
					}
				}
				case SubMenuItem s -> {
					WinDef.HMENU subMenu = user32Ext.CreatePopupMenu();
					if (subMenu == null) {
						throw new TrayMenuException("Failed to create submenu: " + s.title());
					}
					addChildren(subMenu, s.items());
					boolean ok = user32Ext.AppendMenuW(
							menu,
							MF_POPUP,
							new WinDef.UINT_PTR(Pointer.nativeValue(subMenu.getPointer())),
							s.title()
					);
					if (!ok) {
						throw new TrayMenuException("Failed to append submenu: " + s.title());
					}
				}
			}
		}
	}

	private WinDef.LRESULT handleTrayCallback(WinDef.HWND hWnd, WinDef.WPARAM wParam, WinDef.LPARAM lParam) {
		int event = lParam.intValue() & 0xFFFF;

		if (event == WM_LBUTTONDBLCLK) {
			defaultAction.run();
			return new WinDef.LRESULT(0);
		}

		if (event == WM_RBUTTONUP || event == WM_CONTEXTMENU) {
			showPopupMenu(hWnd);
			return new WinDef.LRESULT(0);
		}

		return new WinDef.LRESULT(0);
	}

	private void showPopupMenu(WinDef.HWND hWnd) {
		beforeOpenMenu.run();

		WinDef.POINT point = new WinDef.POINT();
		user32.GetCursorPos(point);
		user32.SetForegroundWindow(hWnd);

		user32Ext.TrackPopupMenu(
				rootMenu,
				TPM_LEFTALIGN | TPM_BOTTOMALIGN | TPM_RIGHTBUTTON,
				point.x,
				point.y,
				0,
				hWnd,
				null
		);

		user32.PostMessage(hWnd, WM_NULL, null, null);
	}

	private WinDef.HWND createMessageWindow() throws TrayMenuException {
		WinDef.HINSTANCE hInstance = Kernel32.INSTANCE.GetModuleHandle(null);

		WinUser.WNDCLASSEX wndClass = new WinUser.WNDCLASSEX();
		wndClass.cbSize = wndClass.size();
		wndClass.lpfnWndProc = windowProc;
		wndClass.hInstance = hInstance;
		wndClass.lpszClassName = WINDOW_CLASS_NAME;
		wndClass.write();

		WinDef.ATOM atom = user32.RegisterClassEx(wndClass);
		int lastError = kernel32.GetLastError();
		if (atom.intValue() == 0 && lastError != 1410) {
			throw new TrayMenuException("Failed to register window class. Error=" + lastError);
		}

		WinDef.HWND handle = user32.CreateWindowEx(
				0,
				WINDOW_CLASS_NAME,
				WINDOW_TITLE,
				0,
				0,
				0,
				0,
				0,
				null,
				null,
				hInstance,
				null
		);

		if (handle == null) {
			throw new TrayMenuException("Failed to create hidden tray window. Error=" + kernel32.GetLastError());
		}
		return handle;
	}

	private Shell32Ext.NOTIFYICONDATA createNotifyIconData(String tooltip) {
		Shell32Ext.NOTIFYICONDATA nid = new Shell32Ext.NOTIFYICONDATA();
		nid.cbSize = nid.size();
		nid.hWnd = hwnd;
		nid.uID = TRAY_ICON_UID;
		nid.guidItem = guidFromUuid(UUID.nameUUIDFromBytes("org.cryptomator.Cryptomator".getBytes(StandardCharsets.UTF_8)));
		nid.uFlags = Shell32Ext.NIF_GUID;

		if (tooltip != null) {
			setTooltip(nid, tooltip);
		}
		return nid;
	}

	private void setTooltip(Shell32Ext.NOTIFYICONDATA nid, String tooltip) {
		char[] chars = tooltip.toCharArray();
		int max = Math.min(chars.length, nid.szTip.length - 1);
		System.arraycopy(chars, 0, nid.szTip, 0, max);
		nid.szTip[max] = '\0';
	}

	private WinDef.HICON loadIcon(String icoPath) {
		WinNT.HANDLE handle = user32Ext.LoadImageW(
				null,
				icoPath,
				IMAGE_ICON,
				0,
				0,
				LR_LOADFROMFILE | LR_DEFAULTSIZE
		);
		if (handle == null) {
			throw new IllegalStateException("Failed to load icon file: " + icoPath + " (error=" + kernel32.GetLastError() + ")");
		}
		return new WinDef.HICON(handle.getPointer());
	}

	private static void checkState(boolean expression, String message) {
		if (!expression) {
			throw new IllegalStateException(message);
		}
	}

	private static Guid.GUID guidFromUuid(UUID uuid) {
		Guid.GUID guid = new Guid.GUID();
		long msb = uuid.getMostSignificantBits();
		long lsb = uuid.getLeastSignificantBits();

		guid.Data1 = (int) (msb >>> 32);
		guid.Data2 = (short) ((msb >>> 16) & 0xFFFF);
		guid.Data3 = (short) (msb & 0xFFFF);
		guid.Data4[0] = (byte) ((lsb >>> 56) & 0xFF);
		guid.Data4[1] = (byte) ((lsb >>> 48) & 0xFF);
		guid.Data4[2] = (byte) ((lsb >>> 40) & 0xFF);
		guid.Data4[3] = (byte) ((lsb >>> 32) & 0xFF);
		guid.Data4[4] = (byte) ((lsb >>> 24) & 0xFF);
		guid.Data4[5] = (byte) ((lsb >>> 16) & 0xFF);
		guid.Data4[6] = (byte) ((lsb >>> 8) & 0xFF);
		guid.Data4[7] = (byte) (lsb & 0xFF);
		return guid;
	}
}