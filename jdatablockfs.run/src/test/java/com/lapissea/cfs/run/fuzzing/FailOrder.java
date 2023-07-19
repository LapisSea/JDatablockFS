package com.lapissea.cfs.run.fuzzing;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public enum FailOrder{
	LEAST_ACTION,
	ORIGINAL_ORDER,
	FAIL_SPEED,
	INDEX,
	COMMON_STACK;
	
	private static String lastFail;
	
	public static FailOrder defaultOrder(){
		var defaultVal = FailOrder.COMMON_STACK;
		var propName   = "test.fuzzing.reportFailOrder";
		return Optional.ofNullable(System.getProperty(propName))
		               .map(String::trim)
		               .map(name -> {
			               var vals = FailOrder.values();
			               return Arrays.stream(vals).filter(e -> e.name().equalsIgnoreCase(name)).findAny().orElseGet(() -> {
				               if(!Objects.equals(lastFail, name)){
					               lastFail = name;
					               System.err.println(propName + " can only be one of " + Arrays.toString(vals) +
					                                  " but is actually \"" + name + "\". Defaulting to " + defaultVal);
				               }
				               return defaultVal;
			               });
		               }).orElse(defaultVal);
	}
}
