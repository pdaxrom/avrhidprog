package com.pdaxrom.avrhidprog;

import java.io.File;
import java.io.UnsupportedEncodingException;

import com.pdaxrom.utils.FileDialog;
import com.pdaxrom.utils.IntelHexLoader;
import com.pdaxrom.utils.SelectionMode;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

public class AVRHidProgActivity extends Activity
		implements View.OnClickListener, Runnable {
	private final static String TAG = "AVRHidProg";
	
	private final static String website_url = "http://cctools.info";
	private final static String boothid_url = "http://www.obdev.at/products/vusb/bootloadhid.html";
	
	private final static int USB_DT_STRING = 0x03;
	private final static int USB_REQ_GET_DESCRIPTOR = 0x06;
	private final static int USBRQ_HID_GET_REPORT = 0x01;
	private final static int USBRQ_HID_SET_REPORT = 0x09;
	private final static int USB_RECIP_INTERFACE = 0x01;
	private final static int USB_HID_REPORT_TYPE_INPUT = 1;
	private final static int USB_HID_REPORT_TYPE_OUTPUT = 2;
	private final static int USB_HID_REPORT_TYPE_FEATURE = 3;

	private final static String IDENT_VENDOR_STRING = "obdev.at";
	private final static String IDENT_PRODUCT_STRING = "HIDBoot";
	
	private final static int REQUEST_OPEN = 1;
	
	private UsbManager mUsbManager;
	private UsbDevice mDevice;
	private UsbDeviceConnection mConnection;
	private UsbEndpoint mEndpoint;
	
	private int pageSize;
	private int flashSize;
	
	private TextView bootHidView;
	private TextView pageSizeView;
	private TextView flashSizeView;
	private EditText pathText;
	private ImageButton pathButton;
	private TextView startAddrView;
	private TextView lengthView;
	private ProgressBar progressBar;
	private ImageButton prgButton;
	private CheckBox resetBox;
	
	private String fileName;
	private IntelHexLoader hexData;
	private Thread prgThread;
	
	//
	// USB_DEVICE_ATTACHED broadcast bug
	// https://android-review.googlesource.com/#/c/38150/
	//
	
	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
	    public void onReceive(Context context, Intent intent) {
	        String action = intent.getAction(); 

	        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
	        	UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
	        	if (device != null) {
	        		setDevice(device);
	        	}
	        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
	            UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
	            if (mDevice != null && mDevice.equals(device)) {
	                // call your method that cleans up and closes communication with the device
	            	Log.i(TAG, "detached!");
	            	setDevice(null);
	            }
	        }
	    }
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_avrhidprog);
		
		bootHidView = (TextView) findViewById(R.id.bootHid);
		pageSizeView = (TextView) findViewById(R.id.pageSize);
		flashSizeView = (TextView) findViewById(R.id.flashSize);
		pathText = (EditText) findViewById(R.id.pathText);
		pathText.setEnabled(false);
		startAddrView = (TextView) findViewById(R.id.startAddr);
		lengthView = (TextView) findViewById(R.id.length);
		progressBar = (ProgressBar) findViewById(R.id.progressBar);
		
		pathButton = (ImageButton) findViewById(R.id.pathButton);
		pathButton.setOnClickListener(this);
		pathButton.setEnabled(false);
		
		prgButton = (ImageButton) findViewById(R.id.prgButton);
		prgButton.setOnClickListener(this);
		prgButton.setEnabled(false);
		
		resetBox = (CheckBox) findViewById(R.id.resetChkBox);
		SharedPreferences settings = getPreferences(Activity.MODE_PRIVATE);
		resetBox.setChecked(settings.getBoolean("leavebootloader", false));
		resetBox.setEnabled(false);
		
		mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
		
		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		registerReceiver(mUsbReceiver, filter);		
	}

	protected void onDestroy() {
		unregisterReceiver(mUsbReceiver);
		super.onDestroy();
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.avrhidprog, menu);
		return true;
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.action_about:
			aboutDialog();
			break;
		}
		return true;
	}
	
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		
		if (resultCode == RESULT_OK) {
			if (requestCode == REQUEST_OPEN) {
				fileName = data.getStringExtra(FileDialog.RESULT_PATH);
				Log.i(TAG, "open file " + fileName);
				pathText.setText(fileName);
				SharedPreferences settings = getPreferences(Activity.MODE_PRIVATE);
				SharedPreferences.Editor editor = settings.edit();
				editor.putString("lastdir", (new File(fileName)).getParent());
				editor.commit();
				progressBar.setProgress(0);
				hexData = new IntelHexLoader(fileName);
				if (hexData.loaded) {
					if (hexData.endAddr > flashSize - 2048) {
						new AlertDialog.Builder(this)
						.setTitle(getString(R.string.no_flash))
						.setMessage(getString(R.string.no_flash_1) + hexData.endAddr + " " + getString(R.string.no_flash_2))
						.show();
					} else {
						startAddrView.setText("0x" + Integer.toHexString(hexData.startAddr));
						lengthView.setText("" + (hexData.endAddr - hexData.startAddr) + " (0x" + Integer.toHexString(hexData.endAddr - hexData.startAddr) + ")");
						prgButton.setEnabled(true);
						resetBox.setEnabled(true);
					}
				} else {
					startAddrView.setText("");
					lengthView.setText("");
					prgButton.setEnabled(false);
					resetBox.setEnabled(false);
					String errMsg;
					switch (hexData.error) {
					case IntelHexLoader.IO_ERROR:
						errMsg = getString(R.string.bad_hex_file_io);
						break;
					case IntelHexLoader.CHECKSUM_ERROR:
						errMsg = getString(R.string.bad_hex_file_chksum);
						break;
					case IntelHexLoader.FORMAT_ERROR:
						errMsg = getString(R.string.bad_hex_file_format);
						break;
					default:
						errMsg = getString(R.string.bad_hex_file_unknwn);
						break;
					}
					new AlertDialog.Builder(this)
					.setTitle(getString(R.string.bad_hex_file_title))
					.setMessage(errMsg)
					.show();
				}
			}
		}
	}
	
	@Override
	public void onResume() {
		super.onResume();
		
		Intent intent = getIntent();
		Log.d(TAG, "intent: " + intent);
		String action = intent.getAction();
		UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
		if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
			setDevice(device);
		} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
			if (mDevice != null && mDevice.equals(device)) {
				setDevice(null);
			}
		}
	}
	
	private void setDevice(UsbDevice device) {
		Log.d(TAG, "setDevice " + device);
		if (device == null) {
			Log.i(TAG, "disable interface - no device");
			mConnection.close();
			mConnection = null;
			mDevice = null;
			bootHidView.setText("");
			pageSizeView.setText("");
			flashSizeView.setText("");
			pathText.setEnabled(false);
			pathButton.setEnabled(false);
			prgButton.setEnabled(false);
			resetBox.setEnabled(false);
			return;
		}
		if (device.getInterfaceCount() != 1) {
			Log.e(TAG, "could not find interface");
			usbErrorDialog(getString(R.string.usb_not_find_interface));
			return;
		}
		UsbInterface intf = device.getInterface(0);
		if (intf.getEndpointCount() != 1) {
			Log.e(TAG, "could not find endpoint");
			usbErrorDialog(getString(R.string.usb_not_find_endpoint));
			return;
		}

		Log.i(TAG,"Model: " + device.getDeviceName());
		Log.i(TAG,"ID: " + device.getDeviceId());
		Log.i(TAG,"Class: " + device.getDeviceClass());
		Log.i(TAG,"Protocol: " + device.getDeviceProtocol());
		Log.i(TAG,"Vendor ID " + device.getVendorId());
		Log.i(TAG,"Product ID: " + device.getProductId());
		Log.i(TAG,"Interface count: " + device.getInterfaceCount());
		Log.i(TAG,"---------------------------------------");
		// Get interface details
		for (int index = 0; index < device.getInterfaceCount(); index++) {
			UsbInterface mUsbInterface = device.getInterface(index);
			Log.i(TAG,"  *****     *****");
			Log.i(TAG,"  Interface index: " + index);
			Log.i(TAG,"  Interface ID: " + mUsbInterface.getId());
			Log.i(TAG,"  Inteface class: " + mUsbInterface.getInterfaceClass());
			Log.i(TAG,"  Interface protocol: " + mUsbInterface.getInterfaceProtocol());
			Log.i(TAG,"  Endpoint count: " + mUsbInterface.getEndpointCount());
			// Get endpoint details 
			for (int epi = 0; epi < mUsbInterface.getEndpointCount(); epi++) {
				UsbEndpoint mEndpoint = mUsbInterface.getEndpoint(epi);
				Log.i(TAG,"    ++++   ++++   ++++");
				Log.i(TAG,"    Endpoint index: " + epi);
				Log.i(TAG,"    Attributes: " + mEndpoint.getAttributes());
				Log.i(TAG,"    Direction: " + mEndpoint.getDirection());
				Log.i(TAG,"    Number: " + mEndpoint.getEndpointNumber());
				Log.i(TAG,"    Interval: " + mEndpoint.getInterval());
				Log.i(TAG,"    Packet size: " + mEndpoint.getMaxPacketSize());
				Log.i(TAG,"    Type: " + mEndpoint.getType());
			}
		}
		
		mDevice = device;
		mEndpoint = intf.getEndpoint(0);

		UsbDeviceConnection connection = mUsbManager.openDevice(device);
		if (connection != null && connection.claimInterface(intf, true)) {
			Log.d(TAG, "open SUCCESS");
			mConnection = connection;

			String iManufacturer = usbGetStringAscii(1, 0x409);
			if (iManufacturer == null) {
				Log.e(TAG, "cannot query manufacturer for device");
				usbErrorDialog(getString(R.string.usb_cant_query_manufacturer));
				return;
			}
			Log.i(TAG, "manufacturer " + iManufacturer);

			String iProduct = usbGetStringAscii(2, 0x409);
			if (iProduct == null) {
				Log.e(TAG, "cannot query product for device");
				usbErrorDialog(getString(R.string.usb_cant_query_product));
				return;
			}
			Log.i(TAG, "product " + iProduct);
			
			bootHidView.setText(iManufacturer + " " + iProduct);
			
			if (iManufacturer.contentEquals(IDENT_VENDOR_STRING) &&
				iProduct.contentEquals(IDENT_PRODUCT_STRING)) {
				// info block:
				// 1 - reportId
				// 2 - pageSize
				// 4 - flashSize
				byte[] info = new byte[7];
				int ret = usbGetReport(USB_HID_REPORT_TYPE_FEATURE, 1, info, info.length);
				if (ret != info.length) {
					Log.e(TAG, "Not enought bytes in device info report (" + ret + " instead of " + info.length + ")");
					usbErrorDialog(getString(R.string.usb_bad_info_report));
				} else {
					pageSize = getUsbInt(info, 1, 2);
					flashSize = getUsbInt(info, 3, 4);
					Log.i(TAG, "page size " + pageSize);
					Log.i(TAG, "flash size " + flashSize);
					
					pageSizeView.setText("" + pageSize + " (0x" + Integer.toHexString(pageSize) + ")");
					flashSizeView.setText("" + flashSize + " (0x" + Integer.toHexString(flashSize) + "), " + (flashSize - 2048) + " " + getString(R.string.flash_free));
					
					pathText.setEnabled(true);
					pathButton.setEnabled(true);
				}
			} else {
				Log.e(TAG, "unsupported HID bootloader " + iManufacturer + "/" + iProduct);
				usbErrorDialog(getString(R.string.usb_unsupported_bootloader) + " " + iManufacturer + "/" + iProduct);
			}
			
		} else {
			Log.d(TAG, "open FAIL");
			mConnection = null;
			usbErrorDialog(getString(R.string.usb_open_failed));
		}		
	}
	
	private int getUsbInt(byte[] buffer, int startByte, int numBytes) {
		int value = 0;
		for (int i = 0; i < numBytes; i++) {
			value |= ((buffer[startByte + i] & 0xff) << (i * 8));
		}
		return value;
	}
	
	private void setUsbInt(byte[] buffer, int value, int startByte, int numBytes) {
		for (int i = 0; i < numBytes; i++) {
			buffer[startByte + i] = (byte) (value & 0xff);
			value >>= 8;
		}
	}
	
	private String usbGetStringAscii(int index, int langid) {
		byte[] buf = new byte[128];
		int ret = mConnection.controlTransfer(UsbConstants.USB_DIR_IN,
				USB_REQ_GET_DESCRIPTOR,
				(USB_DT_STRING << 8) + index,
				langid,
				buf,
				buf.length,
				1000);
		if ((ret < 0) || (buf[1] != USB_DT_STRING)) {
			Log.e(TAG, "Error usbGetStringAscii() " + ret);
			return null;
		}
		if (buf[0] < ret) {
			ret = buf[0];
		}
		try {
			return new String(buf, 2, ret - 2, "UTF-16LE");
		} catch (UnsupportedEncodingException e) {
			Log.e(TAG, "unsupported encoding exception " + e);
		}
		return null;		
	}
	
	private int usbSetReport(int reportType, byte[] buffer, int length) {
		int bytesSent = mConnection.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE,
				USBRQ_HID_SET_REPORT,
				(reportType << 8) | buffer[0],
				0,
				buffer,
				length,
				5000);
		
		if (bytesSent < 0) {
			Log.e(TAG, "Error sending message");
		}
		
		return bytesSent;
	}
	
	private int usbGetReport(int reportType, int reportNumber, byte[] buffer, int length) {
		int bytesReceived = mConnection.controlTransfer(UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE,
				USBRQ_HID_GET_REPORT,
				(reportType << 8) | reportNumber,
				0,
				buffer,
				length,
				5000);
		
		if (bytesReceived < 0) {
			Log.e(TAG, "Error receiving message");
		}
		
		return bytesReceived;
	}
	
	@Override
	public void run() {
		if (mDevice == null) {
			return;
		}
		int mask;
		if (pageSize < 128) {
			mask = 127;
		} else {
			mask = pageSize - 1;
		}
		int startAddr = hexData.startAddr & ~mask;
		int endAddr = (hexData.endAddr + mask) & ~mask;
		int pCount = 0;
		int pMax = endAddr - startAddr;
		progressBar.setMax(pMax);
		progressBar.setProgress(0);
		byte[] buf = new byte[128 + 3 + 1];
		while(startAddr < endAddr) {
			buf[0] = 2;
			setUsbInt(buf, startAddr, 1, 3);
			System.arraycopy(hexData.buffer, startAddr, buf, 4, 128);
			int err = usbSetReport(USB_HID_REPORT_TYPE_FEATURE, buf, buf.length);
			Log.i(TAG, "write block " + Integer.toHexString(startAddr) + " length " + Integer.toHexString(128));
			if (err != buf.length) {
				Log.i(TAG, "error sending message (" + err + " - " + buf.length + ")");
				flashError();
				return;
			}
			Log.i(TAG, "return " + err + " >> " + buf[0] + " " + buf[1] + " " + buf[2] + " " + buf[3] + " " + buf[4] + " " + buf[5]);
			startAddr += 128;
			pCount += 128;
			progressBar.setProgress(pCount);
		}
		progressBar.setProgress(pMax);
		if (resetBox.isChecked()) {
			buf[0] = 1;
			Log.i(TAG, "leave bootloader");
			usbSetReport(USB_HID_REPORT_TYPE_FEATURE, buf, 1 + 2 + 4);
		}
		flashDone();
	}

	@Override
	public void onClick(View v) {
		if (v == pathButton) {
			SharedPreferences settings = getPreferences(Activity.MODE_PRIVATE);
			String dir = settings.getString("lastdir", Environment.getExternalStorageDirectory().getPath());
			if (!(new File(dir).exists())) {
				dir = Environment.getExternalStorageDirectory().getPath();
			}
			Intent intent = new Intent(getBaseContext(), FileDialog.class);
			intent.putExtra(FileDialog.START_PATH, dir);
			intent.putExtra(FileDialog.CAN_SELECT_DIR, false);
			intent.putExtra(FileDialog.SELECTION_MODE, SelectionMode.MODE_OPEN);
			startActivityForResult(intent, REQUEST_OPEN);			
		} else if (v == prgButton) {
			if (prgThread != null) {
				if (prgThread.isAlive()) {
					Log.i(TAG, "thread already running");
					return;
				}
			}
			SharedPreferences settings = getPreferences(Activity.MODE_PRIVATE);
			SharedPreferences.Editor editor = settings.edit();
			editor.putBoolean("leavebootloader", resetBox.isChecked());
			editor.putString("lastdir", (new File(fileName)).getParent());
			editor.commit();
			
			prgThread = new Thread(this);
			prgThread.start();
		}
	}

    private void aboutDialog() {
    	String versionName;
		try {
			versionName = getPackageManager().getPackageInfo(getPackageName(), 0 ).versionName;
		} catch (NameNotFoundException e) {
			versionName = "1.0";
		}
		final TextView textView = new TextView(this);
		textView.setAutoLinkMask(Linkify.WEB_URLS);
		textView.setLinksClickable(true);
		textView.setTextSize(16f);
		textView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
		textView.setText(getString(R.string.about_dialog_text) +
									" " + 
									versionName + 
									"\n" + website_url + "\n" +
									getString(R.string.about_dialog_text2));
		textView.setMovementMethod(LinkMovementMethod.getInstance());
		new AlertDialog.Builder(this)
	    .setTitle(getString(R.string.about_dialog))
	    .setView(textView)
	    .show();
    }

    private void usbErrorDialog(String msg) {
    	new AlertDialog.Builder(this)
    	.setTitle(getString(R.string.usb_error))
    	.setMessage(msg)
    	.show();
    }
    
    final Handler handler = new Handler();
    private Context context = this;
    
    private void flashError() {
    	handler.post(new Runnable() {
    		public void run() {
				new AlertDialog.Builder(context)
				.setTitle(getString(R.string.flash_error_title))
				.setMessage(getString(R.string.flash_error))
				.show();    			
    		}
    	});
    }

    private void flashDone() {
    	handler.post(new Runnable() {
    		public void run() {
				new AlertDialog.Builder(context)
				.setTitle(getString(R.string.firmware_flashed))
				.setMessage(getString(R.string.firmware_flashed_text))
				.setPositiveButton(getString(R.string.button_continue), new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
					}
				})
				.show();    			
    		}
    	});
    }

}
