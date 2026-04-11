package org.cryptomator.windows.tray;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

interface User32Ext extends User32 {

	User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

	WinDef.HMENU CreatePopupMenu();

	boolean DestroyMenu(WinDef.HMENU hMenu);

	boolean AppendMenuW(WinDef.HMENU hMenu, int uFlags, WinDef.UINT_PTR uIDNewItem, String lpNewItem);

	boolean TrackPopupMenu(WinDef.HMENU hMenu, int uFlags, int x, int y, int nReserved, WinDef.HWND hWnd, WinDef.RECT prcRect);

	WinNT.HANDLE LoadImageW(
			WinDef.HINSTANCE hInst,
			String name,
			int type,
			int cx,
			int cy,
			int fuLoad
	);
}