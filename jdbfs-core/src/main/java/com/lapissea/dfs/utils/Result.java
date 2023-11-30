package com.lapissea.dfs.utils;

import java.util.function.Consumer;

public final class Result<Ok, Err>{
	
	public static <Ok, Err> Result<Ok, Err> ok(Ok ok)   { return new Result<>(ok, null, true); }
	public static <Ok, Err> Result<Ok, Err> err(Err err){ return new Result<>(null, err, false); }
	
	private final Ok      ok;
	private final Err     err;
	private final boolean isOk;
	
	private Result(Ok ok, Err err, boolean isOk){
		this.ok = ok;
		this.err = err;
		this.isOk = isOk;
	}
	
	public boolean isOk() { return isOk; }
	public boolean isErr(){ return !isOk; }
	
	public Ok ok(){
		if(!isOk) throw new UnsupportedOperationException("Result is not ok");
		return ok;
	}
	public Err err(){
		if(isOk) throw new UnsupportedOperationException("Result is not an err");
		return err;
	}
	
	public void ifOk(Consumer<Ok> event){
		if(isOk) event.accept(ok);
	}
	public void ifErr(Consumer<Err> event){
		if(!isOk) event.accept(err);
	}
}
