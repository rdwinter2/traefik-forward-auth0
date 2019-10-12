package dniel.forwardauth.application

import com.auth0.jwt.interfaces.Claim
import dniel.forwardauth.AuthProperties
import dniel.forwardauth.AuthProperties.Application
import dniel.forwardauth.domain.authorize.AuthorizeNonce
import dniel.forwardauth.domain.authorize.AuthorizeState
import dniel.forwardauth.domain.authorize.AuthorizeUrl
import dniel.forwardauth.domain.authorize.RequestedUrl
import dniel.forwardauth.domain.authorize.service.Authorizer
import dniel.forwardauth.domain.authorize.service.AuthorizerStateMachine
import dniel.forwardauth.domain.shared.InvalidToken
import dniel.forwardauth.domain.shared.JwtToken
import dniel.forwardauth.domain.shared.User
import dniel.forwardauth.domain.shared.VerifyTokenService
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.net.URI


/**
 * Handle Authorization.
 * This command handler will do all the checking if a user has access or not to a url.
 * As a result of all evaluations in the authorization logic the result will be a set
 * of AuthEvents that will be returned as the result from the handle method.
 * <p/>
 * The handle-method will take all the input and verify according to a set of rules
 * if the user has access the requested url.
 *
 * <p/>
 * Ideas to error handling
 * http://www.douevencode.com/articles/2018-09/kotlin-error-handling/
 * https://medium.com/@spaghetticode/finite-authorizeState-machines-in-kotlin-part-1-57e68d54d93b
 * https://www.codeproject.com/Articles/509234/The-State-Design-Pattern-vs-State-Machine
 * https://github.com/stateless4j
 */
@Component
class AuthorizeHandler(val properties: AuthProperties,
                       val verifyTokenService: VerifyTokenService) : CommandHandler<AuthorizeHandler.AuthorizeCommand> {

    private val LOGGER = LoggerFactory.getLogger(this::class.java)

    /**
     * This is the input parameter object for the handler to pass inn all
     * needed parameters to the handler.
     */
    data class AuthorizeCommand(
            val accessToken: String?,
            val idToken: String?,
            val protocol: String,
            val host: String,
            val uri: String,
            val method: String,
            val isApi: Boolean
    ) : Command


    /**
     * This command can produce a set of events as response from the handle method.
     */
    sealed class AuthEvent : Event {
        class NeedRedirect(val authorizeUrl: URI, val nonce: AuthorizeNonce, val cookieDomain: String) : AuthEvent()
        class AccessGranted(val userinfo: Map<String, String>) : AuthEvent()
        object AccessDenied : AuthEvent()
        class Error(error: Authorizer.Error?) : AuthEvent() {
            val reason: String = error?.message ?: "Unknown error"
        }
    }

    /**
     * Main Handle Command method.
     */
    override fun handle(params: AuthorizeCommand): AuthEvent {
        val authUrl = properties.authorizeUrl
        val app = properties.findApplicationOrDefault(params.host)
        val nonce = AuthorizeNonce.generate()
        val originUrl = RequestedUrl(params.protocol, params.host, params.uri, params.method)
        val state = AuthorizeState.create(originUrl, nonce)
        val authorizeUrl = AuthorizeUrl(authUrl, app, state)
        val cookieDomain = app.tokenCookieDomain
        val isApi = params.isApi

        LOGGER.debug("Authentication: " + SecurityContextHolder.getContext().authentication)
        LOGGER.debug("Authentication: " + SecurityContextHolder.getContext().authentication.principal)

        val accessToken = when (SecurityContextHolder.getContext().authentication) {
            is UsernamePasswordAuthenticationToken -> {
                val currentUser: User = SecurityContextHolder.getContext().authentication.principal as User
                currentUser.accessToken
            }
            is AnonymousAuthenticationToken -> {
                InvalidToken("anonymous user")
            }
            else -> throw IllegalStateException("Neither Authenticated token, or Anonymous Authentication token found.")
        }

        val idToken = when (SecurityContextHolder.getContext().authentication) {
            is UsernamePasswordAuthenticationToken -> {
                val currentUser: User = SecurityContextHolder.getContext().authentication.principal as User
                currentUser.idToken
            }
            is AnonymousAuthenticationToken -> {
                InvalidToken("anonymous user")
            }
            else -> throw IllegalStateException("Neither Authenticated token, or Anonymous Authentication token found.")
        }

        /*
        val user = params.user
        val accessToken = user.accessToken
        val idToken = user.idToken
        */

        val authorizer = Authorizer.create(accessToken, idToken, app, originUrl, isApi)
        val (authorizerState, authorizerError) = authorizer.authorize()

        LOGGER.debug("State: ${authorizerState}")
        LOGGER.debug("Error: ${authorizerError}")

        return when (authorizerState) {
            AuthorizerStateMachine.State.NEED_REDIRECT -> AuthEvent.NeedRedirect(authorizeUrl.toURI(), nonce, cookieDomain)
            AuthorizerStateMachine.State.ACCESS_DENIED -> AuthEvent.AccessDenied
            AuthorizerStateMachine.State.ACCESS_GRANTED -> AuthEvent.AccessGranted(getUserinfoFromToken(app, idToken as JwtToken))
            else -> AuthEvent.Error(authorizerError)
        }
    }

    private fun getUserinfoFromToken(app: Application, token: JwtToken): Map<String, String> {
        app.claims.forEach { s -> LOGGER.trace("Should add Claim from token: ${s}") }
        return token.value.claims
                .onEach { entry: Map.Entry<String, Claim> -> LOGGER.trace("Token Claim ${entry.key}=${getClaimValue(entry.value)}") }
                .filterKeys { app.claims.contains(it) }
                .onEach { entry: Map.Entry<String, Claim> -> LOGGER.trace("Filtered claim ${entry.key}=${getClaimValue(entry.value)}") }
                .mapValues { getClaimValue(it.value) }
                .filterValues { it != null } as Map<String, String>
    }

    private fun getClaimValue(claim: Claim): String? {
        return when {
            claim.asArray(String::class.java) != null -> claim.asArray(String::class.java).joinToString()
            claim.asBoolean() != null -> claim.asBoolean().toString()
            claim.asString() != null -> claim.asString().toString()
            claim.asLong() != null -> claim.asLong().toString()
            else -> null
        }
    }

}