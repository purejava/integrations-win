package org.cryptomator.windows.tray;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

interface Shell32Ext extends StdCallLibrary {

	Shell32Ext INSTANCE = Native.load("shell32", Shell32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

	int NIM_ADD = 0x00000000;
	int NIM_MODIFY = 0x00000001;
	int NIM_DELETE = 0x00000002;
	int NIM_SETVERSION = 0x00000004;

	int NIF_MESSAGE = 0x00000001;
	int NIF_ICON = 0x00000002;
	int NIF_TIP = 0x00000004;
	int NIF_GUID = 0x00000020;

	int NOTIFYICON_VERSION_4 = 4;

	boolean Shell_NotifyIconW(int dwMessage, NOTIFYICONDATA lpData);

	@Structure.FieldOrder({
			"cbSize",
			"hWnd",
			"uID",
			"uFlags",
			"uCallbackMessage",
			"hIcon",
			"szTip",
			"dwState",
			"dwStateMask",
			"szInfo",
			"uVersion",
			"szInfoTitle",
			"dwInfoFlags",
			"guidItem",
			"hBalloonIcon"
	})
	class NOTIFYICONDATA extends Structure {
		public int cbSize;
		public WinDef.HWND hWnd;
		public int uID;
		public int uFlags;
		public int uCallbackMessage;
		public WinDef.HICON hIcon;
		public char[] szTip = new char[128];
		public int dwState;
		public int dwStateMask;
		public char[] szInfo = new char[256];
		public int uVersion;
		public char[] szInfoTitle = new char[64];
		public int dwInfoFlags;
		public Guid.GUID guidItem;
		public WinDef.HICON hBalloonIcon;
	}
}