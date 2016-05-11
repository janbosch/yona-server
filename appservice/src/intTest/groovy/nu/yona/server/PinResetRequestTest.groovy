/*******************************************************************************
 * Copyright (c) 2015 Stichting Yona Foundation
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v.2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server


import groovy.json.*

import java.time.Duration

import nu.yona.server.test.User

class PinResetRequestTest extends AbstractAppServiceIntegrationTest
{
	def 'Cannot verify or clear pin reset request before requesting a pin reset'()
	{
		given:
		User richard = addRichard()

		when:
		User richardAfterGet = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)

		then:
		richardAfterGet.pinResetRequestUrl
		!richardAfterGet.verifyPinResetUrl
		!richardAfterGet.clearPinResetUrl

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Request pin reset'()
	{
		given:
		User richard = addRichard()

		when:
		def response = appService.yonaServer.postJson(richard.pinResetRequestUrl, [:], ["Yona-Password" : richard.password])

		then:
		response.status == 200
		response.responseData.delay == "PT10S"
		User  richardAfterGet = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)
		!richardAfterGet.pinResetRequestUrl

		!richardAfterGet.verifyPinResetUrl
		!richardAfterGet.clearPinResetUrl

		sleep(Duration.parse(response.responseData.delay).toMillis())
		User  richardAfterDelayedGet = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)
		// The below asserts check the path fragments. If one of these asserts fails, the Swagger spec needs to be updated too
		richardAfterDelayedGet.verifyPinResetUrl == richard.url + "/pinResetRequest/verify"
		richardAfterDelayedGet.clearPinResetUrl == richard.url + "/pinResetRequest/clear"

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Verify pin reset confirmation code'()
	{
		given:
		User richard = addRichard()
		def resetRequestResponse = appService.yonaServer.postJson(richard.pinResetRequestUrl, [:], ["Yona-Password" : richard.password])
		sleep(Duration.parse(resetRequestResponse.responseData.delay).toMillis())
		richard = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)

		when:
		def response = appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "1234"}""", ["Yona-Password" : richard.password])

		then:
		response.status == 200
		User  richardAfterGet = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)
		!richardAfterGet.pinResetRequestUrl
		richardAfterGet.verifyPinResetUrl
		richardAfterGet.clearPinResetUrl

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Hacking attempt: Try to verify pin reset confirmation code before end of delay period'()
	{
		given:
		User richard = addRichard()
		appService.yonaServer.postJson(richard.pinResetRequestUrl, [:], ["Yona-Password" : richard.password])

		when:
		def response = appService.yonaServer.postJson(richard.url + "/pinResetRequest/verify", """{"code" : "1234"}""", ["Yona-Password" : richard.password])

		then:
		response.status == 400
		response.responseData.code == "error.pin.reset.request.confirmation.code.mismatch"

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Clear pin reset request before verification'()
	{
		given:
		User richard = addRichard()
		def resetRequestResponse = appService.yonaServer.postJson(richard.pinResetRequestUrl, [:], ["Yona-Password" : richard.password])
		sleep(Duration.parse(resetRequestResponse.responseData.delay).toMillis())
		richard = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)

		when:
		def response = appService.yonaServer.postJson(richard.clearPinResetUrl, [:], ["Yona-Password" : richard.password])

		then:
		response.status == 200
		User  richardAfterGet = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)
		richardAfterGet.pinResetRequestUrl
		!richardAfterGet.verifyPinResetUrl
		!richardAfterGet.clearPinResetUrl
		def verifyPinResetAttemptResponse = appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "1234"}""", ["Yona-Password" : richard.password])
		verifyPinResetAttemptResponse.status == 400
		verifyPinResetAttemptResponse.responseData.code == "error.pin.reset.request.confirmation.code.not.set"

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Clear pin reset request after verification'()
	{
		given:
		User richard = addRichard()
		def resetRequestResponse = appService.yonaServer.postJson(richard.pinResetRequestUrl, [:], ["Yona-Password" : richard.password])
		sleep(Duration.parse(resetRequestResponse.responseData.delay).toMillis())
		appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "1234"}""", ["Yona-Password" : richard.password])
		richard = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)

		when:
		def response = appService.yonaServer.postJson(richard.clearPinResetUrl, [:], ["Yona-Password" : richard.password])

		then:
		response.status == 200
		User  richardAfterGet = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)
		richardAfterGet.pinResetRequestUrl
		!richardAfterGet.verifyPinResetUrl
		!richardAfterGet.clearPinResetUrl

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Hacking attempt: Brute force try verify pin reset request'()
	{
		given:
		User richard = addRichard()
		def resetRequestResponse = appService.yonaServer.postJson(richard.pinResetRequestUrl, [:], ["Yona-Password" : richard.password])
		sleep(Duration.parse(resetRequestResponse.responseData.delay).toMillis())
		richard = appService.getUser(appService.&assertUserGetResponseDetailsPublicDataAndVpnProfile, richard.url, true, richard.password)

		when:
		def response1TimeWrong = appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "12341"}""", ["Yona-Password" : richard.password])
		response1TimeWrong.responseData.remainingAttempts == 4
		appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "12342"}""", ["Yona-Password" : richard.password])
		appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "12343"}""", ["Yona-Password" : richard.password])
		def response4TimesWrong = appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "12344"}""", ["Yona-Password" : richard.password])
		def response5TimesWrong = appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "12345"}""", ["Yona-Password" : richard.password])
		def response6TimesWrong = appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "12346"}""", ["Yona-Password" : richard.password])
		def response7thTimeRight = appService.yonaServer.postJson(richard.verifyPinResetUrl, """{"code" : "1234"}""", ["Yona-Password" : richard.password])

		then:
		response1TimeWrong.status == 400
		response1TimeWrong.responseData.code == "error.pin.reset.request.confirmation.code.mismatch"
		response1TimeWrong.responseData.remainingAttempts == 4
		response4TimesWrong.status == 400
		response4TimesWrong.responseData.code == "error.pin.reset.request.confirmation.code.mismatch"
		response4TimesWrong.responseData.remainingAttempts == 1
		response5TimesWrong.status == 400
		response5TimesWrong.responseData.code == "error.pin.reset.request.confirmation.code.mismatch"
		response5TimesWrong.responseData.remainingAttempts == 0
		response6TimesWrong.status == 400
		response6TimesWrong.responseData.code == "error.pin.reset.request.confirmation.code.too.many.failed.attempts"
		response6TimesWrong.responseData.remainingAttempts == null
		response7thTimeRight.status == 400
		response7thTimeRight.responseData.code == "error.pin.reset.request.confirmation.code.too.many.failed.attempts"

		cleanup:
		appService.deleteUser(richard)
	}
}
