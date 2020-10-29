package org.cryptomator.windows.keychain;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class WinDataProtectionTest {

	@BeforeAll
	public static void setup() {
		System.loadLibrary("integrations");
	}

	@Test
	public void testProtectAndUnprotect() {
		WinDataProtection dataProtection = new WinDataProtection();

		String storedPw = "h€llo wørld123";
		byte[] ciphertext = dataProtection.protect(storedPw.getBytes(), "salt".getBytes());
		Assertions.assertNotNull(ciphertext);

		byte[] shouldBeNull = dataProtection.unprotect(ciphertext, "pepper".getBytes());
		Assertions.assertNull(shouldBeNull);

		byte[] cleartext = dataProtection.unprotect(ciphertext, "salt".getBytes());
		Assertions.assertArrayEquals(storedPw.getBytes(), cleartext);
	}

}