package com.lapissea.jorth;

public enum EnumClass{
	
	FOO(69, "foo :D"),
	BAR(420, "bar :3");
	
	private final int    number;
	private final String text;
	
	EnumClass(int number, String text){
		this.number = number;
		this.text = text;
	}
	
	public int getNumber(){
		return number;
	}
	public String getText(){
		return text;
	}
}
