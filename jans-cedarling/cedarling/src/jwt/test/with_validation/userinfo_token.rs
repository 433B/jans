/*
 * This software is available under the Apache-2.0 license.
 * See https://www.apache.org/licenses/LICENSE-2.0.txt for full text.
 *
 * Copyright (c) 2024, Gluu, Inc.
 */

//! This module contains negative tests for the `userinfo_token` validation.
//!
//! ## Tests Included
//!
//! - **Missing Claims**:
//!   - Tests for errors when the `iss` (Issuer) claim is missing.
//!   - Tests for errors when the `aud` (Audience) claim is missing.
//!   - Tests for errors when the `sub` (Subject) claim is missing.
//!
//! - **Invalid Signature**:
//!   - Tests for errors when the `useinfo_token` has an invalid signature.
//!   - Tests for errors when the `userinfo_token` is expired.
//!   - Tests for errors when the `userinfo_token` has a different `iss` with the access_token
//!   - Tests for errors when the `userinfo_token` has a different `aud` with the access_token
//!   - Tests for errors when the `userinfo_token` has a different `sub` with the id_token

use super::super::*;
use crate::common::policy_store::TrustedIssuer;
use crate::jwt::decoding_strategy::JwtDecodingError;
use crate::jwt::{self, HttpClient, JwtService, TrustedIssuerAndOpenIdConfig};
use jsonwebtoken::Algorithm;
use serde_json::json;

#[test]
/// Tests that [`JwtService::decode_tokens`] returns an error when the `userinfo_token`
/// is missing an `iss` claim.
fn errors_on_missing_iss() {
    test_missing_claim("iss");
}

#[test]
/// Tests that [`JwtService::decode_tokens`] returns an error when the `userinfo_token`
/// is missing an `aud` claim.
fn errors_on_missing_aud() {
    test_missing_claim("aud");
}

#[test]
/// Tests that [`JwtService::decode_tokens`] returns an error when the `userinfo_token`
/// is missing a `sub` claim.
fn errors_on_missing_sub() {
    test_missing_claim("sub");
}

fn test_missing_claim(missing_claim: &str) {
    // initialize mock server to simulate OpenID configuration and JWKS responses
    let mut server = mockito::Server::new();

    // generate keys and setup the encoding keys and JWKS (JSON Web Key Set)
    let (encoding_keys, jwks) = generate_keys();

    // Valid access_token claims
    let access_token_claims = json!({
        "iss": server.url(),
        "aud": "some_aud".to_string(),
        "sub": "some_sub".to_string(),
        "iat": Timestamp::now(),
        "exp": Timestamp::one_hour_after_now(),
        "scopes": "some_scope".to_string(),
    });

    // Valid id_token token claims
    let id_token_claims = json!({
        "iss": server.url(),
        "aud": "some_aud".to_string(),
        "sub": "some_sub".to_string(),
        "iat": Timestamp::now(),
        "exp": Timestamp::one_hour_after_now(),
        "email": "some_email@gmail.com".to_string(),
    });

    // Invalid userinfo_token (missing claims)
    let mut userinfo_token_claims = json!({
        "client_id": "some_aud".to_string(),
        "name": "ferris".to_string(),
        "email": "ferris@gluu.com".to_string(),
        "iat": Timestamp::now(),
    });

    // add claims incrementally if they're not set to missing
    if missing_claim != "iss" {
        userinfo_token_claims["iss"] = serde_json::Value::String(server.url());
    }
    if missing_claim != "aud" {
        userinfo_token_claims["aud"] = serde_json::Value::String("some_aud".to_string());
    }
    if missing_claim != "sub" {
        userinfo_token_claims["sub"] = serde_json::Value::String("some_sub".to_string());
    }
    if missing_claim != "exp" {
        userinfo_token_claims["exp"] =
            serde_json::Value::Number(Timestamp::one_hour_after_now().into());
    }

    // generate the signed token strings
    let tokens = generate_tokens_using_claims(GenerateTokensArgs {
        access_token_claims,
        id_token_claims,
        userinfo_token_claims,
        encoding_keys,
    });

    // setup mock server responses for OpenID configuration and JWKS URIs
    let openid_config_response = json!({
        "issuer": server.url(),
        "jwks_uri": &format!("{}/jwks", server.url()),
        "unexpected": 123123, // a random number used to simulate having unexpected fields in the response
    });
    let openid_conf_mock = server
        .mock("GET", "/.well-known/openid-configuration")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(openid_config_response.to_string())
        .expect_at_least(1)
        .expect_at_most(2)
        .create();
    let jwks_uri_mock = server
        .mock("GET", "/jwks")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(jwks)
        .expect_at_least(1)
        .expect_at_most(2)
        .create();

    let trusted_idp = TrustedIssuerAndOpenIdConfig::fetch(
        TrustedIssuer {
            name: "some_idp".to_string(),
            description: "some_desc".to_string(),
            openid_configuration_endpoint: format!(
                "{}/.well-known/openid-configuration",
                server.url()
            ),
            token_metadata: None,
        },
        &HttpClient::new().expect("should create http client"),
    )
    .expect("openid config should be fetched successfully");
    // key service should fetch the jwks_uri on init

    // initialize JwtService with validation enabled and ES256 as the supported algorithm
    let jwt_service = JwtService::new_with_config(crate::jwt::JwtServiceConfig::WithValidation {
        supported_algs: vec![Algorithm::ES256, Algorithm::HS256],
        trusted_idps: vec![trusted_idp],
    });

    // TODO: jwt service should not call openid config endpoint on init, because all data already has in the config
    // key service should fetch the jwks_uri on init
    openid_conf_mock.assert();

    // decode and validate the tokens
    let decode_result = jwt_service
        .decode_tokens::<serde_json::Value, serde_json::Value, serde_json::Value>(
            &tokens.access_token,
            &tokens.id_token,
            &tokens.userinfo_token,
        );

    // the jsonwebtoken crate checks for missing claims differently depending on
    // the claim so we need to split these asserts into two, unfortunately.
    //
    // - the first case is triggered when deserializing the token onto a struct
    // - the second case is triggered when checking if the claim in the token is equal to the
    //   expected value
    if ["sub"].contains(&missing_claim) {
        let err_string = format!("missing field `{}`", missing_claim);
        assert!(
            matches!(
                decode_result,
                Err(jwt::JwtServiceError::InvalidUserinfoToken(
                    JwtDecodingError::Validation(ref e)
                )) if matches!(e.kind(), jsonwebtoken::errors::ErrorKind::Json(json_err)
                    if json_err.to_string().contains(&err_string))
            ),
            "Expected decoding to fail due to `userinfo_token` missing a required header: {:?}",
            decode_result
        );
    // for missing `exp`, `aud`, and `iss`
    } else {
        assert!(
            matches!(
                decode_result,
                Err(jwt::JwtServiceError::InvalidUserinfoToken(
                    JwtDecodingError::Validation(ref e)
                )) if matches!(
                    e.kind(),
                    jsonwebtoken::errors::ErrorKind::MissingRequiredClaim(req_claim) if req_claim == missing_claim
                ),
            ),
            "Expected decoding to fail due to `userinfo_token` missing a required header: {:?}",
            decode_result
        );
    }

    // assert that there aren't any additional calls to the mock server
    openid_conf_mock.assert();
    jwks_uri_mock.assert();
}

#[test]
/// Tests that [`JwtService::decode_tokens`] returns an error when the `userinfo_token`
/// has an invalid signature
fn errors_on_invalid_signature() {
    // initialize mock server to simulate OpenID configuration and JWKS responses
    let mut server = mockito::Server::new();

    // generate keys and setup the encoding keys and JWKS (JSON Web Key Set)
    let (encoding_keys, jwks) = generate_keys();

    // Valid access_token claims
    let access_token_claims = json!({
        "iss": server.url(),
        "aud": "some_aud".to_string(),
        "sub": "some_sub".to_string(),
        "scopes": "some_scope".to_string(),
        "iat": Timestamp::now(),
        "exp": Timestamp::one_hour_after_now(),
    });

    // Valid id_token token claims
    let id_token_claims = json!({
        "iss": server.url(),
        "aud": "some_aud".to_string(),
        "sub": "some_sub".to_string(),
        "email": "some_email@gmail.com".to_string(),
        "iat": Timestamp::now(),
        "exp": Timestamp::one_hour_after_now(),
    });

    // Valid userinfo_token claims
    let userinfo_token_claims = json!({
        "iss": server.url(),
        "aud": "some_aud".to_string(),
        "sub": "some_sub".to_string(),
        "client_id": "some_aud".to_string(),
        "name": "ferris".to_string(),
        "email": "ferris@gluu.com".to_string(),
        "iat": Timestamp::now(),
        "exp": Timestamp::one_hour_after_now(),
    });

    // generate the signed access_token
    let access_token = generate_token_using_claims(&access_token_claims, &encoding_keys[0])
        .expect("Should generate access_token");

    // generate signed id_token
    let id_token = generate_token_using_claims(&id_token_claims, &encoding_keys[1])
        .expect("Should generate id_token");

    // generate userinfo_token with invalid signature
    let userinfo_token = generate_token_using_claims(&userinfo_token_claims, &encoding_keys[0])
        .expect("Should generate userinfo_token");
    let userinfo_token = invalidate_token(userinfo_token);

    // setup mock server responses for OpenID configuration and JWKS URIs
    let openid_config_response = json!({
        "issuer": server.url(),
        "jwks_uri": &format!("{}/jwks", server.url()),
        "unexpected": 123123, // a random number used to simulate having unexpected fields in the response
    });
    let openid_conf_mock = server
        .mock("GET", "/.well-known/openid-configuration")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(openid_config_response.to_string())
        .expect_at_least(1)
        .expect_at_most(2)
        .create();
    let jwks_uri_mock = server
        .mock("GET", "/jwks")
        .with_status(200)
        .with_header("content-type", "application/json")
        .with_body(jwks)
        .expect_at_least(1)
        .expect_at_most(2)
        .create();

    let trusted_idp = TrustedIssuerAndOpenIdConfig::fetch(
        TrustedIssuer {
            name: "some_idp".to_string(),
            description: "some_desc".to_string(),
            openid_configuration_endpoint: format!(
                "{}/.well-known/openid-configuration",
                server.url()
            ),
            token_metadata: None,
        },
        &HttpClient::new().expect("should create http client"),
    )
    .expect("openid config should be fetched successfully");

    // initialize JwtService with validation enabled and ES256 as the supported algorithm
    let jwt_service = JwtService::new_with_config(crate::jwt::JwtServiceConfig::WithValidation {
        supported_algs: vec![Algorithm::ES256, Algorithm::HS256],
        trusted_idps: vec![trusted_idp],
    });

    // key service should fetch the jwks_uri on init
    openid_conf_mock.assert();
    // key service should fetch the jwks on init
    jwks_uri_mock.assert();

    // decode and validate the tokens
    let decode_result = jwt_service
        .decode_tokens::<serde_json::Value, serde_json::Value, serde_json::Value>(
            &access_token,
            &id_token,
            &userinfo_token,
        );

    // assert that decoding resulted in an error due to missing claims
    assert!(
        matches!(
            decode_result,
            Err(jwt::JwtServiceError::InvalidUserinfoToken(
                JwtDecodingError::Validation(ref e)
            )) if matches!(
                e.kind(),
                jsonwebtoken::errors::ErrorKind::InvalidSignature
            ),
        ),
        "Expected error due to invalid signature from `userinfo_token` during token decoding: {:?}",
        decode_result,
    );

    // assert that there aren't any additional calls to the mock server
    openid_conf_mock.assert();
    jwks_uri_mock.assert();
}
