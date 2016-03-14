/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Portions Copyrighted 2015 ForgeRock AS.
 */

package com.forgerock.authenticator.mechanisms.TOTP;

import android.content.Context;
import android.widget.Toast;

import com.forgerock.authenticator.R;
import com.forgerock.authenticator.ValueStore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.forgerock.authenticator.utils.URIMappingException;

import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.List;

public class TokenPersistence {
    private static final String NAME  = "tokens";
    private static final String ORDER = "tokenOrder";

    private static final TokenFactory tokenFactory = new TokenFactory();

    private final ValueStore valueStore;

    public TokenPersistence(Context context) {
        valueStore = new ValueStore(context, NAME);
    }

    private List<String> getTokenOrder() {
        return valueStore.getList(ORDER);
    }

    private ValueStore setTokenOrder(List<String> order) {
        return valueStore.put(ORDER, order);
    }

    public static Token addWithToast(Context ctx, String uri) {
        try {
            Token token = tokenFactory.get(uri);
            new TokenPersistence(ctx).add(token);
            return token;
        } catch (URIMappingException e) {
            Toast.makeText(ctx, R.string.invalid_token, Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }

        return null;
    }

    public int length() {
        return getTokenOrder().size();
    }

    public Token get(int position) {
        String key = getTokenOrder().get(position);

        try {
            return valueStore.getAsClass(key, Token.class);
        } catch (JsonSyntaxException jse) {
            // Backwards compatibility for URL-based persistence. // Is this needed?
            try {
                return tokenFactory.get(valueStore.getString(key));
            } catch (URIMappingException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    public void add(Token token) {
        String key = token.getID();

        if (valueStore.contains(key))
            return;

        List<String> order = getTokenOrder();
        order.add(0, key);
        setTokenOrder(order).put(key, token).apply();
    }

    public void move(int fromPosition, int toPosition) {
        if (fromPosition == toPosition)
            return;

        List<String> order = getTokenOrder();
        if (fromPosition < 0 || fromPosition > order.size())
            return;
        if (toPosition < 0 || toPosition > order.size())
            return;

        order.add(toPosition, order.remove(fromPosition));
        setTokenOrder(order).apply();
    }

    public void delete(int position) {
        List<String> order = getTokenOrder();
        String key = order.remove(position);
        setTokenOrder(order).remove(key).apply();
    }

    public void save(Token token) {
        valueStore.put(token.getID(), token).apply();
    }
}