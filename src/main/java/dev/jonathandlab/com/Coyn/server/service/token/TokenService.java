package dev.jonathandlab.com.Coyn.server.service.token;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import dev.jonathandlab.com.Coyn.server.exception.CoynAppException;
import dev.jonathandlab.com.Coyn.server.model.entity.token.ServerRefreshTokenEntity;
import dev.jonathandlab.com.Coyn.server.model.request.token.PublicTokenExchangeRequest;
import dev.jonathandlab.com.Coyn.server.model.request.token.ServerTokenRefreshRequest;
import dev.jonathandlab.com.Coyn.server.model.response.token.ServerTokenResponse;
import dev.jonathandlab.com.Coyn.server.model.entity.user.AppUserEntity;
import dev.jonathandlab.com.Coyn.server.repository.AppUserRepository;
import dev.jonathandlab.com.Coyn.server.repository.ServerRefreshTokenRepository;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;

@Service
@Transactional
@AllArgsConstructor
public class TokenService implements ITokenService {

    private PlaidApi plaidApi;
    private AppUserRepository appUserRepository;
    private ServerRefreshTokenRepository serverRefreshTokenRepository;

    @Override
    public LinkTokenCreateResponse getLinkToken() {
        LinkTokenCreateRequestUser user = new LinkTokenCreateRequestUser()
                .clientUserId("user-id");

        DepositoryFilter depositoryFilter = new DepositoryFilter()
                .accountSubtypes(List.of(AccountSubtype.CHECKING));

        LinkTokenAccountFilters accountFilters = new LinkTokenAccountFilters()
                .depository(depositoryFilter);

        LinkTokenCreateRequest linkTokenCreateRequest = new LinkTokenCreateRequest()
                .user(user)
                .clientName("Coyn")
                .products(List.of(Products.AUTH))
                .countryCodes(List.of(CountryCode.US))
                .language("en")
                .accountFilters(accountFilters);

        try {
            return Optional.ofNullable(plaidApi.linkTokenCreate(linkTokenCreateRequest)
                            .execute().body())
                    .orElseThrow(() -> {
                        throw new CoynAppException(HttpStatus.BAD_REQUEST, "Response Null");
                    });
        } catch (IOException e) {
            e.printStackTrace();
            throw new CoynAppException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Override
    public ItemPublicTokenExchangeResponse exchangePublicToken(PublicTokenExchangeRequest publicTokenExchangeRequest) {
        ItemPublicTokenExchangeRequest itemPublicTokenExchangeRequest = new ItemPublicTokenExchangeRequest();
        itemPublicTokenExchangeRequest.setPublicToken(publicTokenExchangeRequest.getPublicToken());
        try {
            return Optional.ofNullable(plaidApi.itemPublicTokenExchange(itemPublicTokenExchangeRequest)
                            .execute().body())
                    .orElseThrow(() -> {
                        throw new CoynAppException(HttpStatus.BAD_REQUEST, "Response Null");
                    });
        } catch (IOException e) {
            e.printStackTrace();
            throw new CoynAppException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Override
    public HashMap<String, String> parsePlaidAccessTokens(String plaidAccessTokens) {
        String[] idPlaidAccessTokenPairs = plaidAccessTokens.split(",");
        HashMap<String, String> idPlaidAccessTokenPairHashMap = new HashMap<>();
        for (String idPlaidAccessTokenPair : idPlaidAccessTokenPairs) {
            String[] pair = idPlaidAccessTokenPair.split("=");
            if (pair.length != 2) {
                continue;
            }
            String id = pair[0];
            String plaidAccessToken = pair[1];
            idPlaidAccessTokenPairHashMap.put(id, plaidAccessToken);
        }
        return idPlaidAccessTokenPairHashMap;
    }

    @Override
    public void invalidatePlaidAccessToken(String plaidAccessToken) {
        ItemAccessTokenInvalidateRequest itemAccessTokenInvalidateRequest = new ItemAccessTokenInvalidateRequest().accessToken(plaidAccessToken);
        try {
            plaidApi.itemAccessTokenInvalidate(itemAccessTokenInvalidateRequest)
                    .execute();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public ServerTokenResponse createServerTokenResponse(String username, List<SimpleGrantedAuthority> authorities) {
        String serverAccessToken = createServerAccessToken(username, authorities);
        String serverRefreshToken = createServerRefreshToken(username);
        return ServerTokenResponse.builder()
                .serverAccessToken(serverAccessToken)
                .serverRefreshToken(serverRefreshToken)
                .build();
    }

    @Override
    public void invalidateServerAccessToken(String serverAccessToken) {

    }

    @Override
    public UsernamePasswordAuthenticationToken validateServerToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256("Secret Password".getBytes());
            JWTVerifier jwtVerifier = JWT.require(algorithm).build();
            DecodedJWT decodedJWT = jwtVerifier.verify(token);
            String email = decodedJWT.getSubject();
            List<SimpleGrantedAuthority> authorities = Arrays.stream(decodedJWT.getClaim("roles")
                    .asArray(String.class)).map(SimpleGrantedAuthority::new).toList();
            return new UsernamePasswordAuthenticationToken(email, null, authorities);
        } catch (JWTVerificationException exception) {
            throw new CoynAppException(HttpStatus.FORBIDDEN, exception.getMessage());
        }
    }

    @Override
    public ServerTokenResponse refreshServerToken(ServerTokenRefreshRequest serverTokenRefreshRequest) {
        String refreshToken = serverTokenRefreshRequest.getRefreshToken();
        DecodedJWT decodedJWT = JWT.decode(refreshToken);
        Long id = decodedJWT.getClaim("id").asLong();
        ServerRefreshTokenEntity serverRefreshTokenEntity = serverRefreshTokenRepository.findById(id)
                .orElseThrow(() -> new CoynAppException(HttpStatus.NOT_FOUND, id + " not found in database"));
        AppUserEntity appUser = serverRefreshTokenEntity.getAppUser();
        List<SimpleGrantedAuthority> authorities = appUser.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName())).toList();
        Date serverRefreshTokenExpirationDate = getDateFromNowInMinutes(5);
        serverRefreshTokenEntity.setExpirationDate(Timestamp.from(serverRefreshTokenExpirationDate.toInstant()));
        serverRefreshTokenRepository.save(serverRefreshTokenEntity);
        String email = appUser.getEmail();
        Algorithm algorithm = Algorithm.HMAC256("Secret Password".getBytes());
        String newServerAccessToken = createServerAccessToken(email, authorities);
        String newServerRefreshToken = JWT.create().withSubject(email).withClaim("id", id).withExpiresAt(serverRefreshTokenExpirationDate)
                .sign(algorithm);
        return ServerTokenResponse.builder().serverRefreshToken(newServerRefreshToken).serverAccessToken(newServerAccessToken).build();
    }

    private String createServerAccessToken(String username, List<SimpleGrantedAuthority> authorities) {
        Algorithm algorithm = Algorithm.HMAC256("Secret Password".getBytes());
        List<String> authoritiesStringList = authorities.stream().map(SimpleGrantedAuthority::getAuthority).toList();
        return JWT.create()
                .withSubject(username)
                .withExpiresAt(getDateFromNowInMinutes(3))
                .withClaim("roles", authoritiesStringList)
                .sign(algorithm);
    }

    private String createServerRefreshToken(String username) {
        Algorithm algorithm = Algorithm.HMAC256("Secret Password".getBytes());
        AppUserEntity appUserEntity = appUserRepository.findAppUserByEmail(username)
                .orElseThrow(() -> {
                    throw new UsernameNotFoundException("User not found in database");
                });
        Date expirationDate = getDateFromNowInMinutes(5);
        ServerRefreshTokenEntity serverRefreshTokenEntity = new ServerRefreshTokenEntity(null, appUserEntity, Timestamp.from(expirationDate.toInstant()));
        ServerRefreshTokenEntity savedServerRefreshTokenEntity = serverRefreshTokenRepository.save(serverRefreshTokenEntity);
        return JWT.create()
                .withSubject(username)
                .withClaim("id", savedServerRefreshTokenEntity.getId())
                .withExpiresAt(expirationDate)
                .sign(algorithm);
    }

    private Date getDateFromNowInHours(int hours) {
        Date now = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.add(Calendar.HOUR, hours);
        return calendar.getTime();
    }

    private Date getDateFromNowInMinutes(int minutes) {
        Date now = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.add(Calendar.MINUTE, minutes);
        return calendar.getTime();
    }
}
