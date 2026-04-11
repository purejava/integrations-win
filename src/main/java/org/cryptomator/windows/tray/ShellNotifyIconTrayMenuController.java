package org.cryptomator.windows.tray;

import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.ShellAPI.NOTIFYICONDATA;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinGDI.ICONINFO;
import com.sun.jna.ptr.PointerByReference;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
	private static final int WMAPP_NOTIFYCALLBACK = WinUser.WM_APP + 1;
	private static final int TRAY_ICON_UID = 1;

	private static final int TPM_LEFTALIGN = 0x0000;
	private static final int TPM_BOTTOMALIGN = 0x0020;
	private static final int TPM_RIGHTBUTTON = 0x0002;

	private final User32 user32 = User32.INSTANCE;
	private final Shell32 shell32 = Shell32.INSTANCE;
	private final Kernel32 kernel32 = Kernel32.INSTANCE;
	private final GDI32 gdi32 = GDI32.INSTANCE;

	private HWND hwnd;
	private HICON trayIconHandle;
	private HMENU rootMenu;
	private Runnable defaultAction = () -> {
	};
	private Runnable beforeOpenMenu = () -> {
	};
	private final Map<Integer, Runnable> commandHandlers = new HashMap<>();
	private int nextCommandId = 1000;

	private final WindowProc windowProc = new WindowProc() {
		@Override
		public LRESULT callback(HWND hWnd, int uMsg, WPARAM wParam, LPARAM lParam) {
			switch (uMsg) {
				case WMAPP_NOTIFYCALLBACK -> {
					return handleTrayCallback(hWnd, lParam);
				}
				case WinUser.WM_COMMAND -> {
					int commandId = lowWord(wParam.intValue());
					Runnable action = commandHandlers.get(commandId);
					if (action != null) {
						action.run();
						return new LRESULT(0);
					}
				}
				case WinUser.WM_DESTROY -> {
					user32.PostQuitMessage(0);
					return new LRESULT(0);
				}
				default -> {
					// fall through
				}
			}
			return user32.DefWindowProc(hWnd, uMsg, wParam, lParam);
		}
	};

	@CheckAvailability
	public static boolean isAvailable() {
		return Platform.isWindows();
	}

	@Override
	public void showTrayIcon(Consumer<TrayIconLoader> iconLoader, Runnable defaultAction, String tooltip) throws TrayMenuException {
		this.defaultAction = defaultAction != null ? defaultAction : () -> {
		};
		this.hwnd = createMessageWindow();
		this.rootMenu = user32.CreatePopupMenu();
		if (this.rootMenu == null) {
			throw new TrayMenuException("Failed to create popup menu.");
		}

		TrayIconLoader.PngData callback = this::showTrayIconWithPngData;
		iconLoader.accept(callback);

		NOTIFYICONDATA nid = createNotifyIconData(tooltip);
		nid.uFlags = new DWORD(NOTIFYICONDATA.NIF_MESSAGE | NOTIFYICONDATA.NIF_ICON | NOTIFYICONDATA.NIF_TIP);
		nid.uCallbackMessage = new UINT(WMAPP_NOTIFYCALLBACK);
		nid.hIcon = trayIconHandle;
		setTooltip(nid, tooltip);

		boolean added = shell32.Shell_NotifyIcon(WinUser.NIM_ADD, nid);
		if (!added) {
			throw new TrayMenuException("Failed to add icon to notification area.");
		}

		NOTIFYICONDATA versionData = createNotifyIconData(tooltip);
		versionData.uVersion = new UINT(WinUser.NOTIFYICON_VERSION_4);
		shell32.Shell_NotifyIcon(WinUser.NIM_SETVERSION, versionData);
	}

	private void showTrayIconWithPngData(byte[] imageData) {
		destroyCurrentIcon();
		trayIconHandle = createHIconFromPng(imageData);
	}

	@Override
	public void updateTrayIcon(Consumer<TrayIconLoader> iconLoader) {
		TrayIconLoader.PngData callback = this::updateTrayIconWithPngData;
		iconLoader.accept(callback);
	}

	private void updateTrayIconWithPngData(byte[] imageData) {
		checkState(hwnd != null, "Tray icon is not setup. Call showTrayIcon(...) first.");
		destroyCurrentIcon();
		trayIconHandle = createHIconFromPng(imageData);

		NOTIFYICONDATA nid = createNotifyIconData(null);
		nid.uFlags = new DWORD(NOTIFYICONDATA.NIF_ICON);
		nid.hIcon = trayIconHandle;

		shell32.Shell_NotifyIcon(WinUser.NIM_MODIFY, nid);
	}

	@Override
	public void updateTrayMenu(List<TrayMenuItem> items) throws TrayMenuException {
		checkState(hwnd != null, "Tray icon is not setup. Call showTrayIcon(...) first.");

		if (rootMenu != null) {
			user32.DestroyMenu(rootMenu);
		}
		rootMenu = user32.CreatePopupMenu();
		commandHandlers.clear();
		nextCommandId = 1000;

		addChildren(rootMenu, items);
	}

	@Override
	public void onBeforeOpenMenu(Runnable runnable) {
		this.beforeOpenMenu = runnable != null ? runnable : () -> {
		};
	}

	private void addChildren(HMENU menu, List<TrayMenuItem> items) throws TrayMenuException {
		for (TrayMenuItem item : items) {
			switch (item) {
				case ActionItem a -> {
					int commandId = nextCommandId++;
					commandHandlers.put(commandId, a.action());

					boolean ok = user32.AppendMenu(
							menu,
							new UINT(WinUser.MF_STRING | (a.enabled() ? 0 : WinUser.MF_GRAYED)),
							new UINT_PTR(commandId),
							a.title()
					);
					if (!ok) {
						throw new TrayMenuException("Failed to append action menu item: " + a.title());
					}
				}
				case SeparatorItem ignored -> {
					boolean ok = user32.AppendMenu(menu, new UINT(WinUser.MF_SEPARATOR), new UINT_PTR(0), null);
					if (!ok) {
						throw new TrayMenuException("Failed to append separator.");
					}
				}
				case SubMenuItem s -> {
					HMENU subMenu = user32.CreatePopupMenu();
					if (subMenu == null) {
						throw new TrayMenuException("Failed to create submenu: " + s.title());
					}
					addChildren(subMenu, s.items());

					boolean ok = user32.AppendMenu(
							menu,
							new UINT(WinUser.MF_POPUP),
							new UINT_PTR(Pointer.nativeValue(subMenu.getPointer())),
							s.title()
					);
					if (!ok) {
						throw new TrayMenuException("Failed to append submenu: " + s.title());
					}
				}
			}
		}
	}

	private LRESULT handleTrayCallback(HWND hWnd, LPARAM lParam) {
		int mouseMessage = lParam.intValue();

		if (mouseMessage == WinUser.WM_LBUTTONDBLCLK) {
			defaultAction.run();
			return new LRESULT(0);
		}

		if (mouseMessage == WinUser.WM_RBUTTONUP || mouseMessage == WinUser.WM_CONTEXTMENU) {
			showPopupMenu(hWnd);
			return new LRESULT(0);
		}

		return new LRESULT(0);
	}

	private void showPopupMenu(HWND hWnd) {
		beforeOpenMenu.run();

		WinDef.POINT point = new WinDef.POINT();
		user32.GetCursorPos(point);
		user32.SetForegroundWindow(hWnd);

		user32.TrackPopupMenu(
				rootMenu,
				TPM_LEFTALIGN | TPM_BOTTOMALIGN | TPM_RIGHTBUTTON,
				point.x,
				point.y,
				0,
				hWnd,
				null
		);

		user32.PostMessage(hWnd, WinUser.WM_NULL, null, null);
	}

	private HWND createMessageWindow() throws TrayMenuException {
		HMODULE hInstance = kernel32.GetModuleHandle(null);

		WinUser.WNDCLASSEX wndClass = new WinUser.WNDCLASSEX();
		wndClass.cbSize = wndClass.size();
		wndClass.lpfnWndProc = windowProc;
		wndClass.hInstance = hInstance;
		wndClass.lpszClassName = WINDOW_CLASS_NAME;

		WinDef.ATOM atom = user32.RegisterClassEx(wndClass);
		if (atom.intValue() == 0) {
			int lastError = kernel32.GetLastError();
			// 1410 == class already exists
			if (lastError != WinError.ERROR_CLASS_ALREADY_EXISTS) {
				throw new TrayMenuException("Failed to register hidden window class. Error=" + lastError);
			}
		}

		HWND handle = user32.CreateWindowEx(
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

	private NOTIFYICONDATA createNotifyIconData(String tooltip) {
		NOTIFYICONDATA nid = new NOTIFYICONDATA();
		nid.cbSize = new DWORD(nid.size());
		nid.hWnd = hwnd;
		nid.uID = new UINT(TRAY_ICON_UID);

		// Optional but useful for robustness on modern Windows:
		GUID guid = guidFromUuid(UUID.nameUUIDFromBytes("org.cryptomator.Cryptomator".getBytes()));
		nid.guidItem = guid;
		nid.uFlags = new DWORD(NOTIFYICONDATA.NIF_GUID);

		if (tooltip != null) {
			setTooltip(nid, tooltip);
		}
		return nid;
	}

	private void setTooltip(NOTIFYICONDATA nid, String tooltip) {
		if (tooltip == null) {
			return;
		}
		char[] chars = tooltip.toCharArray();
		int max = Math.min(chars.length, nid.szTip.length - 1);
		System.arraycopy(chars, 0, nid.szTip, 0, max);
		nid.szTip[max] = '\0';
	}

	private void destroyCurrentIcon() {
		if (trayIconHandle != null) {
			user32.DestroyIcon(trayIconHandle);
			trayIconHandle = null;
		}
	}

	private HICON createHIconFromPng(byte[] pngData) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngData));
			if (image == null) {
				throw new IllegalArgumentException("Unsupported tray icon image format.");
			}

			int width = image.getWidth();
			int height = image.getHeight();
			int[] argb = new int[width * height];
			image.getRGB(0, 0, width, height, argb, 0, width);

			// Windows expects BGRA for a 32-bit DIB.
			Memory colorBits = new Memory((long) width * height * 4);
			int offset = 0;
			for (int y = height - 1; y >= 0; y--) { // bottom-up bitmap
				for (int x = 0; x < width; x++) {
					int pixel = argb[y * width + x];
					byte a = (byte) ((pixel >> 24) & 0xFF);
					byte r = (byte) ((pixel >> 16) & 0xFF);
					byte g = (byte) ((pixel >> 8) & 0xFF);
					byte b = (byte) (pixel & 0xFF);

					colorBits.setByte(offset++, b);
					colorBits.setByte(offset++, g);
					colorBits.setByte(offset++, r);
					colorBits.setByte(offset++, a);
				}
			}

			HBITMAP colorBitmap = gdi32.CreateBitmap(width, height, 1, 32, colorBits);
			HBITMAP maskBitmap = gdi32.CreateBitmap(width, height, 1, 1, Pointer.NULL);

			if (colorBitmap == null || maskBitmap == null) {
				if (colorBitmap != null) {
					gdi32.DeleteObject(colorBitmap);
				}
				if (maskBitmap != null) {
					gdi32.DeleteObject(maskBitmap);
				}
				throw new IllegalStateException("Failed to create icon bitmaps.");
			}

			try {
				ICONINFO iconInfo = new ICONINFO();
				iconInfo.fIcon = true;
				iconInfo.xHotspot = 0;
				iconInfo.yHotspot = 0;
				iconInfo.hbmMask = maskBitmap;
				iconInfo.hbmColor = colorBitmap;

				HICON hIcon = user32.CreateIconIndirect(iconInfo);
				if (hIcon == null) {
					throw new IllegalStateException("Failed to create HICON.");
				}
				return hIcon;
			} finally {
				gdi32.DeleteObject(colorBitmap);
				gdi32.DeleteObject(maskBitmap);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException("Failed to decode tray icon PNG.", e);
		}
	}

	private static int lowWord(int value) {
		return value & 0xFFFF;
	}

	private static void checkState(boolean expression, String message) {
		if (!expression) {
			throw new IllegalStateException(message);
		}
	}

	private static GUID guidFromUuid(UUID uuid) {
		GUID guid = new GUID();
		guid.Data1 = (int) (uuid.getMostSignificantBits() >> 32);
		guid.Data2 = (short) (uuid.getMostSignificantBits() >> 16);
		guid.Data3 = (short) uuid.getMostSignificantBits();

		long lsb = uuid.getLeastSignificantBits();
		guid.Data4[0] = (byte) (lsb >> 56);
		guid.Data4[1] = (byte) (lsb >> 48);
		guid.Data4[2] = (byte) (lsb >> 40);
		guid.Data4[3] = (byte) (lsb >> 32);
		guid.Data4[4] = (byte) (lsb >> 24);
		guid.Data4[5] = (byte) (lsb >> 16);
		guid.Data4[6] = (byte) (lsb >> 8);
		guid.Data4[7] = (byte) lsb;
		return guid;
	}
}