/*
 * Copyright © 2026 Howard Hughes Medical Institute
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of HHMI nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.janelia.flythrough.imported;

import java.lang.reflect.Type;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import net.imglib2.realtransform.AffineTransform3D;

/**
 * Gson adapter that (de)serializes an {@link AffineTransform3D} as a flat array
 * of 12 doubles (row-packed). Copied from
 * {@code org.janelia.saalfeldlab.ispim.AffineTransform3DAdapter}.
 *
 * @author Stephan Preibisch
 */
public class AffineTransform3DAdapter implements JsonDeserializer<AffineTransform3D>, JsonSerializer<AffineTransform3D> {

	@Override
	public AffineTransform3D deserialize(
			final JsonElement json,
			final Type typeOfT,
			final JsonDeserializationContext context) throws JsonParseException {

		final JsonArray array = json.getAsJsonArray();
		final double[] values = new double[array.size()];
		for (int i = 0; i < values.length; ++i)
			values[i] = array.get(i).getAsDouble();
		final AffineTransform3D affine = new AffineTransform3D();
		affine.set(values);
		return affine;
	}

	@Override
	public final JsonElement serialize(
			final AffineTransform3D affine,
			final Type typeOfSrc,
			final JsonSerializationContext context) {

		return context.serialize(affine.getRowPackedCopy());
	}
}
