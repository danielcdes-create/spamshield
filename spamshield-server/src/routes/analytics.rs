use crate::db::AppDatabase;
use crate::models::{
    AnalyticsSummaryResponse, CarrierStatItem, CategoryStatItem, HourlyHeatmapItem, PrefixStatItem,
};
use axum::{extract::State, http::StatusCode, Json};
use bson::{doc, Document};
use futures::TryStreamExt;

/// GET /api/v1/analytics/summary
/// Executes a `$facet` Aggregation Pipeline in MongoDB returning consolidated statistics:
/// - Total intercepted, total blocked, average processing latency
/// - Top 10 attacking prefixes
/// - Carrier distribution
/// - Category distribution
/// - Hourly heatmap (00h..23h)
pub async fn get_analytics_summary(
    State(db): State<AppDatabase>,
) -> Result<Json<AnalyticsSummaryResponse>, (StatusCode, String)> {
    let pipeline = vec![doc! {
        "$facet": {
            "totals": [
                {
                    "$group": {
                        "_id": null,
                        "total_intercepted": { "$sum": 1 },
                        "total_blocked": {
                            "$sum": {
                                "$cond": [
                                    { "$in": ["$decision_vector.outcome", ["BLOCKED", "SHADOW_BLOCKED"]] },
                                    1,
                                    0
                                ]
                            }
                        },
                        "avg_latency": { "$avg": "$decision_vector.processing_latency_ms" }
                    }
                }
            ],
            "top_prefixes": [
                {
                    "$match": {
                        "decision_vector.outcome": { "$in": ["BLOCKED", "SHADOW_BLOCKED"] }
                    }
                },
                {
                    "$group": {
                        "_id": "$call_details.prefix_7",
                        "count": { "$sum": 1 }
                    }
                },
                { "$sort": { "count": -1 } },
                { "$limit": 10 }
            ],
            "by_carrier": [
                {
                    "$group": {
                        "_id": { "$ifNull": ["$call_details.carrier_guess", "Desconhecida"] },
                        "count": { "$sum": 1 }
                    }
                },
                { "$sort": { "count": -1 } }
            ],
            "by_category": [
                {
                    "$group": {
                        "_id": "$decision_vector.category",
                        "count": { "$sum": 1 }
                    }
                },
                { "$sort": { "count": -1 } }
            ],
            "by_hour": [
                {
                    "$group": {
                        "_id": {
                            "$hour": {
                                "date": "$timestamp",
                                "timezone": "America/Sao_Paulo"
                            }
                        },
                        "count": { "$sum": 1 }
                    }
                },
                { "$sort": { "_id": 1 } }
            ]
        }
    }];

    let mut cursor = db
        .call_telemetry()
        .aggregate(pipeline)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    let mut total_intercepted = 0_i64;
    let mut total_blocked = 0_i64;
    let mut avg_processing_latency_ms = 0.0_f64;
    let mut top_attacking_prefixes = Vec::new();
    let mut carrier_distribution = Vec::new();
    let mut category_distribution = Vec::new();
    let mut hourly_heatmap = Vec::new();

    if let Some(facet_doc) = cursor
        .try_next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?
    {
        if let Ok(totals_arr) = facet_doc.get_array("totals") {
            if let Some(first_total) = totals_arr.first().and_then(|b| b.as_document()) {
                total_intercepted = doc_get_i64(first_total, "total_intercepted");
                total_blocked = doc_get_i64(first_total, "total_blocked");
                avg_processing_latency_ms = first_total.get_f64("avg_latency").unwrap_or(0.0);
            }
        }

        if let Ok(prefix_arr) = facet_doc.get_array("top_prefixes") {
            for item in prefix_arr {
                if let Some(d) = item.as_document() {
                    if let Ok(prefix_7) = d.get_str("_id") {
                        top_attacking_prefixes.push(PrefixStatItem {
                            prefix_7: prefix_7.to_string(),
                            count: doc_get_i64(d, "count"),
                        });
                    }
                }
            }
        }

        if let Ok(carrier_arr) = facet_doc.get_array("by_carrier") {
            for item in carrier_arr {
                if let Some(d) = item.as_document() {
                    let carrier = d.get_str("_id").unwrap_or("Desconhecida").to_string();
                    carrier_distribution.push(CarrierStatItem {
                        carrier,
                        count: doc_get_i64(d, "count"),
                    });
                }
            }
        }

        if let Ok(cat_arr) = facet_doc.get_array("by_category") {
            for item in cat_arr {
                if let Some(d) = item.as_document() {
                    let category = d.get_str("_id").unwrap_or("UNKNOWN_UNVERIFIED").to_string();
                    category_distribution.push(CategoryStatItem {
                        category,
                        count: doc_get_i64(d, "count"),
                    });
                }
            }
        }

        if let Ok(hour_arr) = facet_doc.get_array("by_hour") {
            for item in hour_arr {
                if let Some(d) = item.as_document() {
                    let hour = d
                        .get_i32("_id")
                        .or_else(|_| d.get_i64("_id").map(|v| v as i32))
                        .unwrap_or(0);
                    hourly_heatmap.push(HourlyHeatmapItem {
                        hour,
                        count: doc_get_i64(d, "count"),
                    });
                }
            }
        }
    }

    Ok(Json(AnalyticsSummaryResponse {
        total_intercepted,
        total_blocked,
        avg_processing_latency_ms,
        top_attacking_prefixes,
        carrier_distribution,
        category_distribution,
        hourly_heatmap,
    }))
}

fn doc_get_i64(doc: &Document, key: &str) -> i64 {
    if let Ok(v) = doc.get_i64(key) {
        return v;
    }
    if let Ok(v) = doc.get_i32(key) {
        return v as i64;
    }
    if let Ok(v) = doc.get_f64(key) {
        return v as i64;
    }
    0
}
