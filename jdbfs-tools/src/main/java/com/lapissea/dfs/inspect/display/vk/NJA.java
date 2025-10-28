package com.lapissea.dfs.inspect.display.vk;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

public class NJA{
	
	private interface Common{
		interface Kernel32 extends Library{
			Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
			int GetCurrentThreadId();
		}
		
		interface CLib extends Library{
			CLib INSTANCE = Native.load("c", CLib.class);
			long syscall(long number);
		}
		
		
		final class WinCommon implements Common{
			
			@Override
			public long getNativeThreadId(){
				return Kernel32.INSTANCE.GetCurrentThreadId();
			}
		}
		
		final class UnixCommon implements Common{
			
			@Override
			public long getNativeThreadId(){
				// SYS_gettid for x86_64
				return CLib.INSTANCE.syscall(186);
			}
		}
		
		long getNativeThreadId();
	}
	
	private static final Common COMMON = Platform.isWindows()? new Common.WinCommon() : new Common.UnixCommon();
	
	public static long getNativeThreadId(){
		return COMMON.getNativeThreadId();
	}
	
}
