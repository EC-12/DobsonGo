package com.terroroo.dobsongomenu;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* A injecter en SMALI */
        new DobsonGoMod(this);
        /*
        ####### CODE A INJECTER EN SMALI #######
        PKG: com.android.billingclient.api.BillingResult.getResponseCode()
        ########################################

        .method public getResponseCode()I
            .locals 1

            # Supprime l'instruction originale qui lit le champ
            # iget v0, p0, Lcom/android/billingclient/api/BillingResult;->zza:I

            # Met directement la valeur 3 dans v0
            const/4 v0, 0x3

            return v0
        .end method
        */
        /* --------------------- */
    }

}