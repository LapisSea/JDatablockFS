package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.io.IOHook;

import java.io.Closeable;

public abstract class ClosableIOData extends CursorIOData implements Closeable{
	public ClosableIOData(IOHook hook, boolean readOnly){
		super(hook, readOnly);
	}
}
