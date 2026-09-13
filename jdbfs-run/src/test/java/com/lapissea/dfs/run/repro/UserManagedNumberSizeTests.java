package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UserManagedNumberSizeTests{
	public static class SizedValue extends IOInstance.Managed<SizedValue>{
		@IOValue
		private NumberSize valueSize;
		@IOValue
		@IOValue.Unsigned
		@IODependency.NumSize("valueSize")
		private int value;

		public SizedValue(){ }
		SizedValue(NumberSize size, int value){
			this.valueSize = size;
			this.value = value;
		}
	}

	@org.testng.annotations.DataProvider
	public Object[][] values(){
		return new Object[][]{
			{NumberSize.BYTE, 0, true},
			{NumberSize.BYTE, 255, true},
			{NumberSize.BYTE, 256, false},
			{NumberSize.SHORT, 256, true},
			{NumberSize.SHORT, 65535, true},
			{NumberSize.SHORT, 65536, false},
			{NumberSize.INT, 65536, true},
			{NumberSize.INT, Integer.MAX_VALUE, true}
		};
	}

	@Test(dataProvider = "values")
	public void explicitSizeMustFitValue(NumberSize size, int value, boolean fits) throws IOException{
		var provider = DataProvider.newVerySimpleProvider();
		var pipe = StandardStructPipe.of(SizedValue.class);
		var out = new ContentOutputBuilder();
		var instance = new SizedValue(size, value);
		if(!fits){
			assertThatThrownBy(() -> pipe.write(provider, out, instance)).isInstanceOf(VaryingSize.TooSmall.class);
			return;
		}
		pipe.write(provider, out, instance);
		try(var input = new ContentInputStream.BA(out.toByteArray())){
			var read = pipe.readNew(provider, input, null);
			assertThat(read.value).isEqualTo(value);
			assertThat(read.valueSize).isEqualTo(size);
			assertThat(input.read()).isEqualTo(-1);
		}
	}
}

