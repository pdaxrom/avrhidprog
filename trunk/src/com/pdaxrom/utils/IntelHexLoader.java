package com.pdaxrom.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import android.util.Log;

public class IntelHexLoader {
	private final static String TAG = "IntelHexLoader";
	
	public final static int NO_ERROR = 0;
	public final static int CHECKSUM_ERROR = 1;
	public final static int IO_ERROR = 2;
	public final static int FORMAT_ERROR = 3;
	
	public int startAddr;
	public int endAddr;
	public byte[] buffer;
	public boolean loaded = false;
	public int error = NO_ERROR;
		
	public IntelHexLoader(String file) {
		if ((new File(file)).exists()) {
			try {
				int address;
				int base;
				int segment;
				int lineLen;
				int sum;
				startAddr = 65536 + 256;
				endAddr = 0;
				FileInputStream inf = new FileInputStream(file);
				buffer = new byte[65536 + 256];
				while (parseUntilColon(inf) == ':') {
					if (error != NO_ERROR) {
						inf.close();
						return;
					}
					sum = 0;
					lineLen = parseHex(inf, 2);
					sum += lineLen;
					address = parseHex(inf, 4);
					base = address;
					sum += address >> 8;
					sum += address & 0xff;
					segment = parseHex(inf, 2);
					sum += segment;
					if (segment != 0) {
						continue;
					}
					for (int i = 0; i < lineLen; i++) {
						int d = parseHex(inf, 2);
						buffer[address++] = (byte) (d & 0xff);
						sum += d & 0xff;
					}
					sum += parseHex(inf, 2) & 0xff;
					if ((sum & 0xff) != 0) {
						Log.w(TAG, "Checksum error between address 0x" + Integer.toHexString(base) + " and 0x" + Integer.toHexString(address));
						error = CHECKSUM_ERROR;
						inf.close();
						return;
					}
					if (startAddr > base) {
						startAddr = base;
					}
					if (endAddr < address) {
						endAddr = address;
					}
				}
				//Log.i(TAG, "Start 0x" + Integer.toHexString(startAddr) + " End 0x" + Integer.toHexString(endAddr));
				loaded = true;
				inf.close();
			} catch (IOException e) {
				Log.e(TAG, "IOException " + e);
				error = IO_ERROR;
			}
		}
	}
	
	private byte parseUntilColon(FileInputStream inf) {
		int b;
		try {
			do {
				b = inf.read();
			} while ((b != ':') && (b > 0));
			return (byte) (b & 0xff);
		} catch (IOException e) {
			Log.e(TAG, "IOException " + e);
			error = IO_ERROR;
		}
		return 0;
	}
	
	private int parseHex(FileInputStream inf, int numDigits) {
		String hex = "";
		try {
			for (int i = 0; i < numDigits; i++) {
				byte[] tmp = new byte[1];
				tmp[0] = (byte) (inf.read() & 0xff);
				hex += (new String(tmp));
			}
			//Log.i(TAG, "parseHex [" + hex + "] [" + Integer.parseInt(hex, 16) + "]");
			return Integer.parseInt(hex, 16);
		} catch(IOException e) {
			Log.e(TAG, "IOException " + e);
			error = IO_ERROR;
		} catch (NumberFormatException e) {
			Log.e(TAG, "NumberFormatException " + e);
			error = FORMAT_ERROR;
		}
		
		return 0;
	}
}
