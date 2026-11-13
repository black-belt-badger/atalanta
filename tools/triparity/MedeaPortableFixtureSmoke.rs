//! Experimental consumer-only decoder check of Atalanta fixture set 1.0.0.

use std::fs;
use std::path::Path;
use crate::account::decode_account;
use crate::assets::decode_assets;
use crate::positions::decode_positions;
use crate::orders::decode_orders;
use crate::order_lookup::decode_order_lookup;
use crate::cli::OrderId;
use crate::client::ClientError;
use serde_json::Value;

#[test]
fn provider_decode_and_http_status_on_all_26_portable_cases() {
    let root = std::env::var("ATALANTA_CONTRACT_ROOT")
        .expect("ATALANTA_CONTRACT_ROOT should point to sanitized fixture pack");
    let root = Path::new(&root);
    let contents = fs::read(root.join("manifest.json")).unwrap();
    let manifest: Value = serde_json::from_slice(&contents).unwrap();
    let cases = manifest["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 26);

    let mut mismatches = Vec::new();
    for case in cases {
        let id = case["id"].as_str().unwrap();
        let endpoint = case["operation"].as_str().unwrap();
        let expected = case["expected"]["outcome"].as_str().unwrap();
        let status = case["response"]["status"].as_u64().unwrap() as u16;
        let filename = case["response"]["body"].as_str().unwrap();
        let body = fs::read(root.join(filename)).unwrap();

        if status == 200 {
            let decoded: Result<(), String> = match endpoint {
                "account" => decode_account(&body).map(|_| ()).map_err(|e| e.to_string()),
                "assets" => decode_assets(&body).map(|_| ()).map_err(|e| e.to_string()),
                "positions" => decode_positions(&body).map(|_| ()).map_err(|e| e.to_string()),
                "orders" => decode_orders(&body).map(|_| ()).map_err(|e| e.to_string()),
                "order_by_id" => {
                    let identifier = case["input"]["id"].as_str().unwrap().parse::<OrderId>().unwrap();
                    decode_order_lookup(&identifier, Ok(body.clone())).map(|_| ())
                }
                _ => panic!("Unknown fixture operation: {endpoint}"),
            };
            let accepted = decoded.is_ok();
            if accepted != (expected == "ok") {
                let error = decoded.err().unwrap_or_default();
                println!("PORTABLE-SMOKE MISMATCH {id}: expected={expected}, accepted={accepted}, reason={error}");
                mismatches.push(id.to_string());
            }
        } else {
            // Non-200 responses are interpreted by the authenticated HTTP
            // boundary, never fed to the JSON response decoders.
            assert!(expected == "error" || expected == "absent", "{id}");
            if endpoint == "order_by_id" {
                let identifier = case["input"]["id"].as_str().unwrap().parse::<OrderId>().unwrap();
                let message = decode_order_lookup(&identifier, Err(ClientError::UnexpectedStatus(status))).unwrap_err();
                assert!(message.contains("HTTP 404"), "{id}: {message}");
            } else {
                assert!(matches!(status, 401 | 429 | 503), "{id}");
            }
        }
        if !mismatches.iter().any(|failed| failed == id) {
            println!("PORTABLE-SMOKE PASS {id}");
        }
    }
    assert!(mismatches.is_empty(), "Portable decoder mismatches: {mismatches:?}");
}
