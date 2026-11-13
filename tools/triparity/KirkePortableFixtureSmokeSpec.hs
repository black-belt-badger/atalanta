{-# LANGUAGE OverloadedStrings #-}

-- Experimental offline smoke against Atalanta's portable provider fixtures.
-- Tests decoder acceptance and HTTP status only, not normalized field values.
module Alpaca.PortableFixtureSmokeSpec (spec) where

import Alpaca.Client (AlpacaError (..), interpretPaperResponse)
import Alpaca.Command.Order (parseOrderId)
import Alpaca.Command.Order.Run (decodeOrderLookup)
import Control.Monad (void)
import Alpaca.Domain.Account (decodeAccount)
import Alpaca.Domain.Asset (decodeAssets)
import Alpaca.Domain.Position (decodePositions)
import Alpaca.Domain.Order (decodeOrder, decodeOrders)
import Data.Aeson (FromJSON (..), eitherDecodeStrict', withObject, (.:), (.:?))
import qualified Data.ByteString as BS
import Data.Text (Text)
import qualified Data.Text as T
import System.Environment (getEnv)
import System.FilePath ((</>))
import Test.Hspec (Spec, describe, it, expectationFailure, runIO)

data PortableCase = PortableCase
  { caseId :: Text
  , operation :: Text
  , responseStatus :: Int
  , responsePath :: FilePath
  , expectedOutcome :: Text
  , inputOrderId :: Maybe Text
  }

newtype Manifest = Manifest [PortableCase]

instance FromJSON Manifest where
  parseJSON = withObject "Manifest" $ \o -> Manifest <$> o .: "cases"

instance FromJSON PortableCase where
  parseJSON = withObject "PortableCase" $ \o -> do
    name <- o .: "id"
    endpoint <- o .: "operation"
    (status, body) <- o .: "response" >>= withObject "Response" (\r ->
      (,) <$> r .: "status" <*> r .: "body")
    outcome <- o .: "expected" >>= withObject "Expected" (.: "outcome")
    identifier <- o .: "input" >>= withObject "Input" (.:? "id")
    pure $ PortableCase name endpoint status body outcome identifier

spec :: Spec
spec = do
  root <- runIO $ getEnv "ATALANTA_CONTRACT_ROOT"
  manifest <- runIO $ BS.readFile (root </> "manifest.json")
  case eitherDecodeStrict' manifest of
    Left why -> it "parse portable manifest" $ expectationFailure why
    Right (Manifest items) -> describe "Atalanta 1.0.0 portable decoder smoke" $
      mapM_ (check root) items

check :: FilePath -> PortableCase -> Spec
check root c = it (T.unpack (caseId c)) $ do
  body <- BS.readFile (root </> responsePath c)
  let decoded = case operation c of
        "account" -> void (decodeAccount body)
        "assets" -> void (decodeAssets body)
        "positions" -> void (decodePositions body)
        "orders" -> void (decodeOrders body)
        "order_by_id" ->
          case inputOrderId c of
            Nothing -> Left "missing requested UUID in fixture input"
            Just identifier -> case parseOrderId (T.unpack identifier) of
              Left err -> Left err
              Right requested -> void (decodeOrderLookup requested (Right body))
        _ -> Left "unknown fixture endpoint"
      status = responseStatus c
  let conformant =
        if status == 200 then
          either (const False) (const True) decoded == (expectedOutcome c == "ok")
        else case interpretPaperResponse status body of
          Left (UnexpectedStatus code) -> code == status
          _ -> False
  if conformant then
    putStrLn ("PORTABLE-SMOKE PASS " <> T.unpack (caseId c))
  else do
    putStrLn ("PORTABLE-SMOKE MISMATCH " <> T.unpack (caseId c))
    expectationFailure ("decoder/status discrepancy: " <> show decoded)
