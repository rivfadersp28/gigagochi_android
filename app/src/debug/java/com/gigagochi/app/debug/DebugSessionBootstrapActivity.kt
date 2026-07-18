package com.gigagochi.app.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gigagochi.app.MainActivity
import com.gigagochi.app.core.auth.SessionMutationResult
import com.gigagochi.app.core.auth.androidSessionRepository
import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.PetLocalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DebugSessionBootstrapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val encodedPayload = intent.getStringExtra(DebugSessionPayloadExtra)
        intent.removeExtra(DebugSessionPayloadExtra)
        val session = DebugSessionPayloadParser.parse(encodedPayload, System.currentTimeMillis())
        if (session == null) {
            reject()
            return
        }

        lifecycleScope.launch {
            val sessionRepository = androidSessionRepository(applicationContext)
            if (sessionRepository.save(session) != SessionMutationResult.Success) {
                reject()
                return@launch
            }

            val petSaved = try {
                val database = GigagochiDatabase.build(applicationContext)
                try {
                    AccountPetLifecycle(PetLocalRepository(database)).save(
                        session.accountId,
                        debugTestPetFixture(),
                    )
                } finally {
                    database.close()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!petSaved) {
                sessionRepository.clear()
                reject()
                return@launch
            }

            setResult(Activity.RESULT_OK)
            Toast.makeText(
                applicationContext,
                "Debug-сессия сохранена",
                Toast.LENGTH_SHORT,
            ).show()
            startActivity(
                Intent(this@DebugSessionBootstrapActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
            finish()
        }
    }

    private fun reject() {
        setResult(Activity.RESULT_CANCELED)
        Toast.makeText(
            applicationContext,
            "Debug-сессия отклонена",
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }
}
