package com.lapissea.cfs.query;

public abstract class QueryExecutor{
	
	private static QueryExecutor instance;
	public static QueryExecutor getInstance(){
		if(instance==null) instance=new ReflectionExecutor();
		return instance;
	}
	
	public abstract Object getValue(QueryContext ctx, QueryValueSource arg);
	public abstract boolean executeCheck(QueryContext ctx, QueryCheck check);
	
	public static Object getValueDef(QueryContext ctx, QueryValueSource arg){
		return getInstance().getValue(ctx, arg);
	}
	public static boolean executeCheckDef(QueryContext ctx, QueryCheck check){
		return getInstance().executeCheck(ctx, check);
	}
	
}
