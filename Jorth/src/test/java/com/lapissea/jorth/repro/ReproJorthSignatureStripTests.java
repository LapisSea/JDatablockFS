package com.lapissea.jorth.repro;

import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthSignatureStripTests{

	@Test
	void nonGenericParametersAreUnchanged(){
		var sig = new FunctionInfo.Signature("control", List.of(GenericType.INT, GenericType.STRING));
		assertThat(sig.name()).isEqualTo("control");
		assertThat(sig.args()).containsExactly(GenericType.INT, GenericType.STRING);
	}

	@Test
	void parameterizedSignatureMatchesRawLookupKey(){
		var raw = new FunctionInfo.Signature("takeList", List.of(GenericType.of(List.class)));
		var parameterized = new FunctionInfo.Signature("takeList", List.of(GenericType.of(List.class).withArgs(String.class)));
		assertThat(parameterized).isEqualTo(raw);
		assertThat(Map.of(raw, "found").get(parameterized)).isEqualTo("found");
	}

	@Test
	void stripsAllGenericParametersAndPreservesArrayDimensions(){
		var list = GenericType.of(List.class).withArgs(String.class);
		var array = list.arrayType();
		var input = new ArrayList<JType>(List.of(GenericType.INT, list, GenericType.STRING, array));
		var sig = new FunctionInfo.Signature("mixed", input);
		assertThat(sig.args()).containsExactly(GenericType.INT, GenericType.of(List.class), GenericType.STRING,
		                                      GenericType.of(List.class).arrayType());
		assertThat(input).containsExactly(GenericType.INT, list, GenericType.STRING, array);
		assertThat(list.hasArgs()).isTrue();
		assertThat(array.hasArgs()).isTrue();
		input.clear();
		assertThat(sig.args()).hasSize(4);
	}

	@Test(expectedExceptions = UnsupportedOperationException.class)
	void normalizedArgumentsRemainImmutable(){
		var sig = new FunctionInfo.Signature("takeList", List.of(GenericType.of(List.class).withArgs(String.class)));
		sig.args().clear();
	}
}
