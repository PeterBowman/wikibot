$( function () {
	var $content, $results, $summaryLimit, $summaryStart, $summaryEnd, $timestamp, $total,
		$templates, $stats, $paginators,
		$container = $( '#mw-content-text' ),
		URL = 'plwikt-missing-plwiki-backlinks/api',
		TIMEOUT = 5000,
		currentLimit = plwiktMissingPlwikiBacklinks.limit,
		currentOffset = plwiktMissingPlwikiBacklinks.offset,
		disableClicks = false,
		initialRequest = true;
	
	function queryNodes( $el ) {
		$content = $el.find( '#plwikt-missing-plwiki-backlinks-content' );
		$results = $el.find( '#plwikt-missing-plwiki-backlinks-results' );
		$summaryLimit = $el.find( '#plwikt-missing-plwiki-backlinks-limit' );
		$summaryStart = $el.find( '#plwikt-missing-plwiki-backlinks-start' );
		$summaryEnd = $el.find( '#plwikt-missing-plwiki-backlinks-end' );
		$timestamp = $el.find( '#plwikt-missing-plwiki-backlinks-timestamp' );
		$total = $el.find( '#plwikt-missing-plwiki-backlinks-total' );
		$templates = $( '#plwikt-missing-plwiki-backlinks-templates' );
		$stats = $el.find( '#plwikt-missing-plwiki-backlinks-stats' );
		$paginators = $el.find( '.paginator' );
	}
	
	function makeRequest( params ) {
		return $.ajax( {
			dataType: 'json',
			url: URL + params,
			timeout: TIMEOUT
		} );
	}
	
	function readPaginatorData( $el ) {
		switch ( $el.parent().attr( 'class' ) ) {
			case 'paginator-prev':
				currentOffset -= currentLimit;
				break;
			case 'paginator-next':
				currentOffset += currentLimit;
				break;
			case 'paginator-limits':
				currentLimit = $el.text();
				break;
		}
		
		currentOffset = Math.max( currentOffset, 0 );
		currentLimit = Math.max( currentLimit, 0 );
	}
	
	function handlePaginators( data, $el ) {
		$paginators.find( '.paginator-prev-value, .paginator-next-value' ).text( currentLimit );
		
		$paginators.find( '.paginator-prev, .paginator-next' ).each( function () {
			var $this = $( this ),
				$a = $this.find( 'a' ),
				isNext = $this.hasClass( 'paginator-next' );
			
			if ( !isNext && currentOffset === 0 || isNext && currentOffset + currentLimit > data.total ) {
				$a.replaceWith( $a.html() );
			} else {
				if ( !$a.length ) {
					$a = $( '<a>' )
						.html( $this.html() )
						.appendTo( $this.empty() );
				}
				
				$a.attr( 'href', '?' + $.param( {
					offset: currentOffset + currentLimit * ( isNext ? 1 : -1 ),
					limit: currentLimit
				} ) );
			}
		} );
		
		$paginators.find( '.paginator-limits > a' ).each( function () {
			var $this = $( this );
			
			$this.attr( 'href', '?' + $.param( {
				offset: currentOffset,
				limit: $this.text()
			} ) );
		} );
	}
	
	function updateResults( data ) {
		$timestamp.text( data.timestamp );
		$total.text( data.total );
		
		$summaryLimit.text( currentLimit );
		$summaryStart.text( currentOffset + 1 );
		$summaryEnd.text( Math.min( currentOffset + currentLimit, data.total ) );
		
		data.results.length > plwiktMissingPlwikiBacklinks.columnThreshold
			? $results.addClass( 'column-list' )
			: $results.removeClass( 'column-list' );
		
		$results.html( $.map( data.results, function ( item ) {
			var out = '<a class="wikilink" target="_blank" title="' + item.plwiktTitle +
				'" href="https://pl.wiktionary.org/wiki/' + encodeURI( item.plwiktTitle ) + '#pl' +
				'" data-target="' + item.plwiktTitle + '">' + item.plwiktTitle + '</a>';
			
			if ( item.plwikiRedir ) {
				out += ' ↔ <a class="wikilink redirect" target="_blank" title="' + item.plwikiRedir +
					'" href="https://pl.wikipedia.org/w/index.php?redirect=no&title=' + encodeURI( item.plwikiRedir ) +
					'">w:' + item.plwikiRedir + '</a>';
			}
			
			out += ' ↔ <a class="wikilink' + ( item.missingPlwikiArticle ? ' new' : '' ) + 
				'" target="_blank" title="' + item.plwikiTitle +
				'" href="https://pl.wikipedia.org/wiki/' + encodeURI( item.plwikiTitle ) +
				'">w:' + item.plwikiTitle + '</a>';
			
			if ( item.plwiktBacklinks ) {
				out += '  • <i>linkuje do:</i> ' + $.map( item.plwiktBacklinks, function ( backlink ) {
					return '<a class="wikilink" target="_blank" title="' + backlink +
						'" href="https://pl.wiktionary.org/wiki/' + encodeURI( backlink ) + '#pl' +
						'" data-target="' + backlink + '">' + backlink + '</a>';
				} ).join( ', ' );
			}
			
			return '<li>' + out + '</li>';
		} ).join( '' ) ).attr( 'start', currentOffset + 1 );
		
		$templates.html( $.map( data.templates, function ( template ) {
			return '<a target="_blank" title="Szablon:' + template +
				'" href="https://pl.wikipedia.org/wiki/Szablon:' + encodeURI( template ) +
				'">' + template + '</a>';
		} ).join( ', ' ) + '.' );
		
		$stats.find( '#plwikt-missing-plwiki-backlinks-stats-totalTemplateTransclusions' ).text( data.stats.totalTemplateTransclusions );
		$stats.find( '#plwikt-missing-plwiki-backlinks-stats-targetedTemplateTransclusions' ).text( data.stats.targetedTemplateTransclusions );
		$stats.find( '#plwikt-missing-plwiki-backlinks-stats-targetedArticles' ).text( data.stats.targetedArticles );
		$stats.find( '#plwikt-missing-plwiki-backlinks-stats-foundArticles' ).text( data.stats.foundArticles );
		$stats.find( '#plwikt-missing-plwiki-backlinks-stats-foundRedirects' ).text( data.stats.foundRedirects );
		$stats.find( '#plwikt-missing-plwiki-backlinks-stats-filteredTitles' ).text( data.stats.filteredTitles );
	}
	
	function pushHistoryState( data, url ) {
		var paginatorValues;
		
		if ( !window.history ) {
			return;
		}
		
		paginatorValues = {
			offset: currentOffset,
			limit: currentLimit	
		};
		
		history[url !== undefined ? 'pushState' : 'replaceState']( $.extend( {
			data: data
		}, paginatorValues ), '', location.origin + location.pathname + (
			url || location.search || '?' + $.param( paginatorValues )
		) );
	}
	
	$container.on( 'click', '.paginator a', function ( evt ) {
		var $this = $( this ),
			href = $this.attr( 'href' );
		
		if ( disableClicks ) {
			return false;
		}
		
		evt.preventDefault();
		
		$content.addClass( 'content-loading' );
		disableClicks = true;
		
		return $.when(
			makeRequest( href ),
			initialRequest ? makeRequest( location.search ) : null
		).then( function ( data, initial ) {
			if ( initialRequest ) {
				pushHistoryState( initial[ 0 ] );
				initialRequest = false;
			}
			
			readPaginatorData( $this );
			handlePaginators( data[ 0 ] );
			updateResults( data[ 0 ] );
			$content.removeClass( 'content-loading' );
			disableClicks = false;
			pushHistoryState( data[ 0 ], href );
		},  function ( jqXHR, textStatus, errorThrown ) {
			location.search = href;
		} );
	} );
	
	window.onpopstate = function ( evt ) {
		if ( evt.state ) {
			currentOffset = evt.state.offset;
			currentLimit = evt.state.limit;
			handlePaginators( evt.state.data );
			updateResults( evt.state.data );
			queryNodes( $container );
		}
	};
	
	queryNodes( $container );
	
	$( '.wikilink[data-section^="polski"]' ).definitionPopups();
} );
