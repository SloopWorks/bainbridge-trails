package com.bainbridgetrails.data

import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Fixtures
import com.bainbridgetrails.net.MeDto
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.Reply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRepositoryTest {
    private val server = FakeServer()
    private val path = DataFixtures.tmpDir("profile") + "/profile.json"
    private var signedIn = true

    private fun repo() = ProfileRepository(server.client(token = { if (signedIn) "t" else null }), path, isSignedIn = { signedIn })

    private fun me(custom: String? = null, anon: String = "Mossy Heron 42"): MeDto =
        ApiClient.json.decodeFromString(MeDto.serializer(), Fixtures.me(anon = anon, custom = custom))

    @Test
    fun anonNameIsGeneratedOnceAndPersisted() = runTest {
        val first = repo().load().anonName
        assertTrue(AnonNames.isAnonName(first))
        assertEquals(first, repo().load().anonName)
        assertEquals(first, repo().load().displayName)
    }

    @Test
    fun hideFromBoardSavesAndRevertsOnFailure() = runTest {
        server.on("GET /v1/me", Reply.json(Fixtures.me()))
        val p = repo()
        p.refresh()
        server.on("PUT /v1/me", Reply.json(Fixtures.me().replace("\"hiddenFromBoard\":false", "\"hiddenFromBoard\":true")))
        assertIs<com.bainbridgetrails.net.ApiResult.Ok<*>>(p.setHiddenFromBoard(true))
        assertEquals("""{"hiddenFromBoard":true}""", server.last("PUT /v1/me").body)
        assertTrue(p.profile.value!!.me!!.hiddenFromBoard)

        // Offline: the optimistic flip is undone (no offline queue for this toggle).
        server.online = false
        assertIs<com.bainbridgetrails.net.ApiResult.Failure>(p.setHiddenFromBoard(false))
        assertTrue(p.profile.value!!.me!!.hiddenFromBoard)
    }

    @Test
    fun moderationHideIsReadAndMakesTheToggleMessageNeutral() = runTest {
        val modHidden = Fixtures.me().replace("\"hiddenFromBoard\":false", "\"hiddenFromBoard\":false,\"hiddenByModeration\":true")
        server.on("GET /v1/me", Reply.json(modHidden))
        val p = repo()
        p.refresh()
        val me = p.profile.value!!.me!!
        assertTrue(me.hiddenByModeration)
        // Turning the toggle off doesn't bring the row back: no "You're back on the board".
        assertEquals(HideToggleMessage.UPDATED, hideToggleMessage(hide = false, me))
        assertEquals(HideToggleMessage.UPDATED, hideToggleMessage(hide = true, me))
        val plain = me.copy(hiddenByModeration = false)
        assertEquals(HideToggleMessage.HIDDEN, hideToggleMessage(hide = true, plain))
        assertEquals(HideToggleMessage.SHOWN, hideToggleMessage(hide = false, plain))
        // Older servers omit the field.
        server.on("GET /v1/me", Reply.json(Fixtures.me()))
        p.refresh()
        assertEquals(false, p.profile.value!!.me!!.hiddenByModeration)
    }

    @Test
    fun hideFromBoardNeedsSignIn() = runTest {
        signedIn = false
        assertIs<com.bainbridgetrails.net.ApiResult.Failure>(repo().setHiddenFromBoard(true))
        assertEquals(0, server.requests.size)
    }

    @Test
    fun invalidCustomNameIsNotSent() = runTest {
        assertEquals(NameSaveResult.Invalid(NameCheck.TooShort), repo().saveCustomName("ab"))
        assertEquals(0, server.requests.size)
    }

    @Test
    fun customNameSaved() = runTest {
        server.on("PUT /v1/me", Reply.json(Fixtures.me(custom = "Trail Fox")))
        val p = repo()
        assertIs<NameSaveResult.Saved>(p.saveCustomName("  Trail   Fox "))
        assertTrue("\"displayName\":\"Trail Fox\"" in server.last("PUT /v1/me").body)
        assertEquals("Trail Fox", p.profile.value!!.displayName)
        assertNull(p.profile.value!!.pending)
    }

    @Test
    fun offlineSaveIsKeptAndFlushedLater() = runTest {
        server.online = false
        val p = repo()
        assertEquals(NameSaveResult.SavedLocally, p.saveCustomName("Trail Fox"))
        assertEquals("Trail Fox", p.profile.value!!.displayName)

        // Survives a restart, then goes out on the next flush.
        server.online = true
        server.on("PUT /v1/me", Reply.json(Fixtures.me(custom = "Trail Fox")))
        val restarted = repo()
        assertEquals("Trail Fox", restarted.load().pending?.customName)
        assertIs<NameSaveResult.Saved>(restarted.flushPending())
        assertNull(restarted.profile.value!!.pending)
        assertNull(repo().load().pending)
    }

    @Test
    fun rejectedNameKeepsOldName() = runTest {
        server.on("GET /v1/me", Reply.json(Fixtures.me(custom = "Old Name")))
        val p = repo()
        p.refresh()
        server.on("PUT /v1/me", Reply.error(400, "name_rejected"))
        assertEquals(NameSaveResult.Rejected, p.saveCustomName("Bad Word"))
        assertEquals("Old Name", p.profile.value!!.displayName)
        assertNull(p.profile.value!!.pending)

        server.on("PUT /v1/me", Reply.error(429, "name_rate_limited"))
        assertEquals(NameSaveResult.RateLimited, p.saveCustomName("Other Name"))
        assertEquals("Old Name", p.profile.value!!.displayName)
    }

    @Test
    fun goingAnonymousSendsNullAndAnonName() = runTest {
        server.on("PUT /v1/me", Reply.json(Fixtures.me(anon = "Calm Otter 77")))
        val p = repo()
        val anon = "Calm Otter 77"
        assertIs<NameSaveResult.Saved>(p.saveAnonymous(anon))
        assertEquals("""{"displayName":null,"anonName":"Calm Otter 77"}""", server.last("PUT /v1/me").body)
        assertEquals(anon, p.profile.value!!.anonName)
    }

    @Test
    fun signedOutSaveIsLocalAndSentAfterSignIn() = runTest {
        signedIn = false
        val p = repo()
        assertEquals(NameSaveResult.SavedLocally, p.saveCustomName("Trail Fox"))
        assertEquals(0, server.requests.size)

        signedIn = true
        server.on("PUT /v1/me", Reply.json(Fixtures.me(custom = "Trail Fox")))
        p.onSignedIn(me())
        assertEquals(1, server.count("PUT /v1/me"))
        assertEquals("Trail Fox", p.profile.value!!.displayName)
    }

    @Test
    fun signInAdoptsAccountAnonName() = runTest {
        val p = repo()
        val local = p.load().anonName
        val server = if (local == "Sleepy Newt 31") "Calm Otter 77" else "Sleepy Newt 31"
        p.onSignedIn(me(anon = server))
        assertEquals(server, p.profile.value!!.anonName)
        assertEquals(server, repo().load().anonName)
        assertNotEquals(local, p.profile.value!!.anonName)

        p.onSignedOut(wipe = false)
        assertNull(p.profile.value!!.me)
        assertEquals(server, p.profile.value!!.anonName)
    }

    @Test
    fun pendingNameSurvivesSignOutUnlessWiped() = runTest {
        server.online = false
        val p = repo()
        assertEquals(NameSaveResult.SavedLocally, p.saveCustomName("Trail Fox"))
        p.onSignedOut(wipe = false) // e.g. a 401 while offline changes were queued
        assertEquals("Trail Fox", p.profile.value!!.pending?.customName)
        assertEquals("Trail Fox", repo().load().pending?.customName, "persisted")
        p.onSignedOut(wipe = true) // account deleted
        assertNull(p.profile.value!!.pending)
    }

    @Test
    fun blockedNameStatus() = runTest {
        server.on("GET /v1/me", Reply.json(Fixtures.me().replace("\"anonymous\"", "\"blocked\"")))
        val p = repo()
        p.refresh()
        assertTrue(p.profile.value!!.nameBlocked)
    }
}
